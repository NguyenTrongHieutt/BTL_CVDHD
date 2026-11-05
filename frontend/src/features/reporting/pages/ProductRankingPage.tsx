// Sản phẩm bán chạy, bán chậm (FR-32). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
import { PagePlaceholder } from '../../../shared/components';

export function ProductRankingPage() {
  return (
    <PagePlaceholder
      title="Sản phẩm bán chạy, bán chậm"
      frs={['FR-32 (BC-04)']}
      phase="GĐ1"
      owner="Lân"
      description="Xếp hạng theo số lượng bán và theo doanh thu trong khoảng thời gian được chọn (Q4)."
    />
  );
}
