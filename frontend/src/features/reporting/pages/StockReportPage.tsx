// Báo cáo Tồn kho (FR-33). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function StockReportPage() {
  return (
    <PagePlaceholder
      title="Báo cáo Tồn kho"
      frs={['FR-33 (BC-05)']}
      phase="GĐ1"
      owner="Lân"
      description="Số lượng và giá trị Tồn kho theo Giá vốn, theo Sản phẩm và theo Danh mục (Q5)."
    />
  );
}
