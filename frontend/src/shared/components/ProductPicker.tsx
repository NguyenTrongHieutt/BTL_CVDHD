/**
 * Chọn hàng cho giỏ ở màn bán hàng (FR-9, FR-35). Người giữ: Anh. CHƯA LÀM: đây là khung giữ chỗ.
 *
 * Hợp đồng props đã chốt để các màn khác (bán hàng, Phiếu nhập, kiểm kho) dùng chung.
 *
 * TODO(Anh, DH-01/FR-9, GĐ1):
 *   - Quét mã vạch (máy quét gõ mã + Enter) → thêm thẳng vào giỏ, không cần bấm thêm.
 *   - Gõ tên → Tìm kiếm thường (SP-05 của Hiếu).
 *   - Lấy tên, giá, Tồn kho bằng `GET /api/v1/sales/sellable?ids=`; hiện dấu cảnh báo tồn thấp (FR-26).
 *   - Sản phẩm có Biến thể thì bắt chọn Biến thể (FR-7).
 * TODO(Anh, TH-01, GĐ2): chuyển sang `searchProducts()` trong `shared/api/aiSearch.ts` (Lân):
 *   chờ tối đa 1,5 giây, quá giờ tự về Tìm kiếm thường (AD-12); hiện chế độ đang dùng.
 */
import { Space, Typography } from 'antd';
import type { SellableItem } from '../types/sales';
import { SearchInput } from './SearchInput';

export interface ProductPickerProps {
  /** Gọi khi người bán chọn một Biến thể (quét mã, Tìm kiếm thường hoặc Tìm kiếm ngữ nghĩa). */
  onPick: (item: SellableItem) => void;
  /** Có cho chọn hàng đã hết tồn không (Phiếu nhập: có; bán hàng: không, FR-9). */
  allowOutOfStock?: boolean;
  /** Bật Tìm kiếm ngữ nghĩa (chỉ màn bán hàng, FR-35). */
  semantic?: boolean;
  autoFocus?: boolean;
  disabled?: boolean;
  placeholder?: string;
}

export function ProductPicker({
  autoFocus,
  disabled,
  placeholder = 'Quét mã vạch hoặc gõ tên sản phẩm',
}: ProductPickerProps) {
  return (
    <Space orientation="vertical" style={{ width: '100%' }}>
      <SearchInput autoFocus={autoFocus} disabled={disabled ?? true} placeholder={placeholder} onSearch={() => {}} />
      <Typography.Text type="secondary">Ô chọn hàng sẽ làm ở GĐ1 — Anh (DH-01); Tìm kiếm ngữ nghĩa gắn ở TH-01.</Typography.Text>
    </Space>
  );
}
