// Tồn kho và cảnh báo tồn thấp (FR-26, FR-27). Người giữ: Anh — features/inventory (AD-20).
// Nhân viên xem Tồn kho nhưng không thấy Giá vốn (DTO không có trường này, AD-16) và không đặt ngưỡng.
import { PagePlaceholder } from '../../../shared/components';

export function StockPage() {
  return (
    <PagePlaceholder
      title="Tồn kho"
      frs={['FR-26', 'FR-27']}
      phase="GĐ1"
      owner="Anh"
      description="Tồn kho hiện tại theo Biến thể, dấu cảnh báo tồn thấp; Chủ cửa hàng đặt Ngưỡng tồn tối thiểu. Mặc định ẩn hàng ngừng kinh doanh."
    />
  );
}
