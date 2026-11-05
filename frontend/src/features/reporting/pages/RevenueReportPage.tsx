// Báo cáo doanh thu (FR-30, xuất file FR-34). Người giữ: Lân — features/reporting (AD-20). Chỉ Chủ cửa hàng.
// Số liệu lấy từ `GET /api/v1/reporting/q/Q1` và `Q2` (AD-10); khoảng ngày dùng DateRangePicker (AD-19).
import { PagePlaceholder } from '../../../shared/components';

export function RevenueReportPage() {
  return (
    <PagePlaceholder
      title="Báo cáo doanh thu"
      frs={['FR-30 (BC-02)', 'FR-34 (BC-06)']}
      phase="GĐ1"
      owner="Lân"
      description="Doanh thu và số Đơn hàng theo ngày, tuần, tháng hoặc khoảng tùy chọn, kèm biểu đồ (Recharts). Xuất Excel/CSV làm ở GĐ2."
    />
  );
}
