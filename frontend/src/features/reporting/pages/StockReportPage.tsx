// Báo cáo Tồn kho (FR-33). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
import { Link } from 'react-router';
import { PagePlaceholder } from '../../../shared/components';

export function StockReportPage() {
  return (
    <PagePlaceholder
      title="Báo cáo Tồn kho"
      frs={['FR-33 (BC-05)']}
      phase="GĐ1"
      owner="Lân"
      description={<Link to="/inventory/stock">Xem số lượng tồn hiện tại</Link>}
    />
  );
}
