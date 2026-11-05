/**
 * Kiểu dữ liệu của module `catalog`: Sản phẩm, Biến thể, Danh mục, Khách hàng, Nhà cung cấp.
 * Người giữ: Hiếu (B). Chỉ giữ những kiểu mà feature khác cần; phần còn lại Hiếu bổ sung khi chốt API (CB-03).
 */
import type { Uuid } from './common';

/** Trạng thái kinh doanh. Ngừng kinh doanh là thao tác một chiều (FR-3, AD-14). */
export type ProductStatus = 'ACTIVE' | 'INACTIVE';

/** Khách hàng rút gọn, dùng cho CustomerPicker ở màn bán hàng (FR-14, FR-19). */
export interface CustomerSummary {
  customerId: Uuid;
  name: string;
  /** Số điện thoại, duy nhất trong một Cửa hàng (FR-18). */
  phone: string;
}

// TODO(Hiếu, FR-1/FR-5, GĐ1): kiểu Product, ProductVariant, Category và tham số Tìm kiếm thường.
// TODO(Hiếu, FR-23, GĐ1): kiểu Supplier.
