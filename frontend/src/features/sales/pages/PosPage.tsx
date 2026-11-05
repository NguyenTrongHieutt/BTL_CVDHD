// Bán hàng tại quầy (FR-9 – FR-14). Người giữ: Anh — features/sales (AD-20).
// Màn này thiết kế cho chiều rộng từ 1024px (Consistency Conventions, dòng "Giao diện").
// AD-8: không tự tính tiền; gọi `POST /api/v1/sales/orders/quote` (debounce 300 ms) mỗi khi giỏ đổi
// và chỉ hiện số backend trả về (MoneyText). Giỏ lưu theo `cart/cartStorage.ts`.
import { Card, Col, Row } from 'antd';
import { CustomerPicker, PagePlaceholder, ProductPicker } from '../../../shared/components';

export function PosPage() {
  return (
    <PagePlaceholder
      title="Bán hàng"
      frs={['FR-9', 'FR-10', 'FR-11', 'FR-12', 'FR-13', 'FR-14']}
      phase="GĐ1"
      owner="Anh"
      description="GĐ1: giỏ hàng, giảm giá, tính tiền (DH-01 – DH-03). GĐ2: thanh toán và VietQR, in hóa đơn, gắn Khách hàng (DH-04 – DH-06); Tìm kiếm ngữ nghĩa (TH-01)."
    >
      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <Card title="Thêm hàng vào giỏ">
            <ProductPicker onPick={() => {}} semantic />
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title="Khách hàng">
            <CustomerPicker onChange={() => {}} allowQuickCreate />
          </Card>
        </Col>
      </Row>
    </PagePlaceholder>
  );
}
