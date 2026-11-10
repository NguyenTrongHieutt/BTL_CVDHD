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

/**
 * Một dòng của `GET /api/v1/catalog/categories` (FR-4, SP-04), sắp theo tên không dấu, không phân biệt hoa thường.
 * Cả hai vai trò đọc được (ô lọc ở màn Sản phẩm); thêm, đổi tên, xóa chỉ Chủ cửa hàng.
 */
export interface Category {
  id: Uuid;
  name: string;
  /** Số Sản phẩm: mọi Sản phẩm thuộc Danh mục đều tính, kể cả Sản phẩm đã ngừng kinh doanh. Lớn hơn 0 thì không xóa được. */
  productCount: number;
}

/** Body của `POST /api/v1/catalog/categories` và `PUT /api/v1/catalog/categories/{id}`. Tên bắt buộc, tối đa 100 ký tự. */
export interface CategoryRequest {
  name: string;
}

// TODO(Hiếu, FR-1/FR-5, GĐ1): kiểu Product, ProductVariant và tham số Tìm kiếm thường.
// TODO(Hiếu, FR-23, GĐ1): kiểu Supplier.
