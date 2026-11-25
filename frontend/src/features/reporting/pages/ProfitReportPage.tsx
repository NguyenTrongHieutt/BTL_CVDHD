// Báo cáo lợi nhuận (FR-31). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function ProfitReportPage() {
  return (
    <PagePlaceholder
      title="Báo cáo lợi nhuận"
      frs={['FR-31 (BC-03)']}
      phase="GĐ2"
      owner="Lân"
    />
  );
}
