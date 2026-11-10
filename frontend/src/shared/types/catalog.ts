/**
 * Kiểu dữ liệu của module `catalog`: Sản phẩm, Biến thể, Danh mục, Khách hàng, Nhà cung cấp.
 * Người giữ: Hiếu (B). Chỉ giữ những kiểu mà feature khác cần; phần còn lại Hiếu bổ sung khi chốt API (CB-03).
 */
import type { IsoDateTime, Money, Quantity, Uuid } from './common';

/** Trạng thái kinh doanh. Ngừng kinh doanh là thao tác một chiều (FR-3, AD-14). */
export type ProductStatus = 'ACTIVE' | 'INACTIVE';

/** Khách hàng rút gọn, dùng cho CustomerPicker ở màn bán hàng (FR-14, FR-19). */
export interface CustomerSummary {
  customerId: Uuid;
  /** `null` khi tạo nhanh chỉ bằng số điện thoại (FR-14). */
  name: string | null;
  /** Số điện thoại dạng `0…` (10 chữ số), duy nhất trong một Cửa hàng (FR-18). */
  phone: string;
}

/**
 * Khách hàng, từ `GET /api/v1/catalog/customers` (có tìm kiếm), `GET …/{id}`, `POST`, `PUT` (FR-18 KH-01, FR-19 KH-02).
 * Cả hai vai trò. Không có điểm tích lũy (KH-04 của sales).
 */
export interface Customer extends CustomerSummary {
  /** `null` khi bỏ trống. */
  note: string | null;
  createdAt: IsoDateTime;
}

/**
 * Body của `POST /api/v1/catalog/customers` và `PUT /api/v1/catalog/customers/{id}` (PUT thay toàn bộ: `name`, `note`
 * thiếu thì xóa). Không có `Idempotency-Key`.
 */
export interface CustomerRequest {
  /** Bắt buộc: `0` + 9 chữ số hoặc `+84` + 9 chữ số, được có dấu cách, dấu chấm, gạch ngang; backend lưu dạng `0…`. */
  phone: string;
  /** Tối đa 100 ký tự. */
  name?: string;
  /** Tối đa 500 ký tự, được xuống dòng. */
  note?: string;
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

/**
 * Biến thể của Sản phẩm (AD-6: Tồn kho tính theo Biến thể). Sản phẩm chưa có nhiều Biến thể (FR-7, GĐ3) thì chỉ có
 * một Biến thể mặc định (`isDefault`, `name` là `null`). SKU và mã vạch duy nhất trong một Cửa hàng.
 */
export interface ProductVariant {
  id: Uuid;
  sku: string;
  barcode: string | null;
  /** Nhãn Biến thể, ví dụ "Đỏ / L"; `null` với Biến thể mặc định. */
  name: string | null;
  /** Giá bán, số nguyên đồng (AD-8). */
  salePrice: Money;
  isDefault: boolean;
  status: ProductStatus;
}

/**
 * Sản phẩm, từ `GET /api/v1/catalog/products`, `GET /api/v1/catalog/products/{id}` và `POST` (FR-1, SP-01).
 * Cả hai vai trò đọc được nên không có Giá vốn (AD-15, AD-16). `variants`: Biến thể mặc định đứng đầu.
 */
export interface Product {
  id: Uuid;
  name: string;
  categoryId: Uuid | null;
  categoryName: string | null;
  /** Đơn vị tính, mặc định "cái". */
  unit: string;
  description: string | null;
  hasVariants: boolean;
  status: ProductStatus;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  variants: ProductVariant[];
}

/**
 * Body của `POST /api/v1/catalog/products` (Chủ cửa hàng), kèm header `Idempotency-Key` (AD-7). Tiền là số nguyên
 * đồng, số lượng là số nguyên. Trường bỏ trống thì không gửi.
 */
export interface CreateProductRequest {
  /** Bắt buộc, tối đa 200 ký tự. */
  name: string;
  /** Danh mục của Cửa hàng. */
  categoryId?: Uuid;
  /** Tối đa 30 ký tự; bỏ trống thì backend dùng "cái". */
  unit?: string;
  /** Tối đa 2000 ký tự, được xuống dòng. */
  description?: string;
  /** Bắt buộc, ≥ 0. */
  salePrice: Money;
  /** Tối đa 50 ký tự; bỏ trống thì backend sinh `SP-` + 8 ký tự. */
  sku?: string;
  /** Tối đa 50 ký tự, chỉ chữ cái không dấu và chữ số. */
  barcode?: string;
  /** Tồn kho đầu kỳ, ≥ 0, mặc định 0. */
  openingQuantity?: Quantity;
  /** Giá vốn ban đầu, ≥ 0, mặc định 0; phải lớn hơn 0 khi có Tồn kho đầu kỳ. Chỉ gửi đi, không bao giờ đọc về. */
  openingUnitCost?: Money;
}

/**
 * Bộ lọc của `GET /api/v1/catalog/products` (FR-5, SP-05), cả hai vai trò. Trường bỏ trống thì không lọc.
 * Mặc định sắp theo tên không dấu. Lọc theo Tồn kho không có ở đây: thuộc `GET /api/v1/sales/sellable?inStock=`.
 */
export interface ProductSearchParams {
  /**
   * Tìm kiếm thường: tên Sản phẩm chứa `q` (không phân biệt hoa thường, không cần dấu), hoặc SKU, mã vạch bắt đầu
   * bằng `q`. Backend chuẩn NFC, cắt khoảng trắng; rỗng thì bỏ qua.
   */
  q?: string;
  categoryId?: Uuid;
  /** Bỏ trống là mọi trạng thái. */
  status?: ProductStatus;
  /** Giá bán của Biến thể mặc định, gồm cả hai đầu; `minPrice > maxPrice` bị backend từ chối (`400`). */
  minPrice?: Money;
  maxPrice?: Money;
}

// TODO(Hiếu, FR-23, GĐ1): kiểu Supplier.
