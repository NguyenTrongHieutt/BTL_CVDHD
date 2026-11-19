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
 * Biến thể của Sản phẩm (AD-6: Tồn kho tính theo Biến thể). Sản phẩm một Biến thể (`hasVariants: false`) chỉ có một
 * Biến thể mặc định (`isDefault`, `name` là `null`, `attributes` rỗng); Sản phẩm nhiều Biến thể (FR-7, SP-07) có từ 2
 * Biến thể, mỗi Biến thể có nhãn. SKU và mã vạch duy nhất trong một Cửa hàng.
 */
export interface ProductVariant {
  id: Uuid;
  sku: string;
  barcode: string | null;
  /** Nhãn Biến thể, ví dụ "Đỏ / L"; `null` với Biến thể của Sản phẩm một Biến thể. */
  name: string | null;
  /** Thuộc tính tên → giá trị, ví dụ `{ "Màu": "Đỏ", "Size": "L" }` (SP-07); `{}` khi không có. Thứ tự khóa không giữ. */
  attributes: Record<string, string>;
  /** Giá bán, số nguyên đồng (AD-8). */
  salePrice: Money;
  isDefault: boolean;
  status: ProductStatus;
}

/** Giá bán thấp nhất, cao nhất của các Biến thể đang bán (SP-07), gồm cả hai đầu. */
export interface PriceRange {
  min: Money;
  max: Money;
}

/**
 * Sản phẩm, từ `GET /api/v1/catalog/products`, `GET /api/v1/catalog/products/{id}`, `POST` (FR-1, SP-01), `PUT`
 * (FR-2, SP-02) và `PUT …/{id}/image` (SP-01b).
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
  /**
   * Ảnh (SP-01b): `/api/v1/catalog/products/{id}/image?v=<phiên bản>`, dùng thẳng làm `<img src>` (cùng địa chỉ nên
   * cookie phiên tự gửi kèm; đổi ảnh là đổi URL). `null` khi chưa có ảnh.
   */
  imageUrl: string | null;
  /** `true` khi có nhiều Biến thể (FR-7, SP-07); chỉ đổi từ `false` sang `true` (thêm Biến thể). */
  hasVariants: boolean;
  status: ProductStatus;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  /** Khoảng giá của các Biến thể đang bán (SP-07); `null` khi không còn Biến thể nào đang bán. */
  priceRange: PriceRange | null;
  /** Mọi Biến thể chưa xóa mềm (cả đã ngừng kinh doanh), Biến thể mặc định đứng đầu, rồi theo thứ tự tạo. */
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
 * Một Biến thể khi tạo (FR-7, SP-07): phần tử `variants` của `CreateProductWithVariantsRequest`, hoặc thêm vào Sản phẩm
 * có sẵn (`AddVariantRequest`). Cùng quy tắc SKU, mã vạch, giá, tồn đầu kỳ với `CreateProductRequest`.
 */
export interface VariantInput {
  /** Nhãn, tối đa 100 ký tự; bỏ trống thì backend ghép giá trị thuộc tính bằng " / " (theo thứ tự gửi). */
  name?: string;
  /**
   * 0–3 cặp tên → giá trị, mỗi chuỗi tối đa 50 ký tự. Phải có nhãn hoặc thuộc tính. Nhãn ghép theo thứ tự gửi lên, nhưng
   * `attributes` đọc lại (jsonb) không đảm bảo thứ tự khóa: muốn biết thứ tự thì dựa vào nhãn.
   */
  attributes?: Record<string, string>;
  /** Tối đa 50 ký tự; bỏ trống thì backend sinh `SP-` + 8 ký tự. */
  sku?: string;
  /** Tối đa 50 ký tự, chỉ chữ cái không dấu và chữ số. */
  barcode?: string;
  /** Bắt buộc, ≥ 0. */
  salePrice: Money;
  /** Tồn kho đầu kỳ riêng của Biến thể, ≥ 0, mặc định 0. */
  openingQuantity?: Quantity;
  /** Giá vốn ban đầu, > 0 khi có Tồn kho đầu kỳ. Chỉ gửi đi, không bao giờ đọc về. */
  openingUnitCost?: Money;
}

/**
 * Body của `POST /api/v1/catalog/products` cho Sản phẩm nhiều Biến thể (SP-07), kèm `Idempotency-Key`: giá, SKU, mã
 * vạch, tồn đầu kỳ nằm ở từng Biến thể (cấp Sản phẩm phải bỏ trống). 2–50 Biến thể, cái đầu là mặc định. Lỗi theo
 * trường của Biến thể có đường dẫn `variants[<i>].<trường>` (`i` từ 0).
 */
export interface CreateProductWithVariantsRequest {
  /** Bắt buộc, tối đa 200 ký tự. */
  name: string;
  categoryId?: Uuid;
  unit?: string;
  description?: string;
  variants: VariantInput[];
}

/**
 * Body của `POST /api/v1/catalog/products/{id}/variants` (SP-07), kèm `Idempotency-Key`. Sản phẩm đang một Biến thể
 * thì phải có `existingVariantName`: nhãn cho Biến thể hiện có.
 */
export interface AddVariantRequest extends VariantInput {
  existingVariantName?: string;
}

/**
 * Body của `PUT /api/v1/catalog/products/{id}/variants/{variantId}` (SP-07), **thay toàn bộ**: `name` trống thì ghép
 * giá trị thuộc tính, `sku` trống thì giữ SKU hiện tại, `barcode` trống thì xóa. Đổi giá thì backend ghi Nhật ký.
 */
export interface UpdateVariantRequest {
  name?: string;
  attributes?: Record<string, string>;
  sku?: string;
  barcode?: string;
  /** Bắt buộc, ≥ 0. */
  salePrice: Money;
}

/**
 * Body của `PUT /api/v1/catalog/products/{id}` (FR-2, SP-02), chỉ Chủ cửa hàng. **Thay toàn bộ**: `categoryId`,
 * `description`, `barcode` thiếu thì xóa, `unit` thiếu thì "cái"; riêng `sku` thiếu thì giữ SKU hiện tại. `salePrice`,
 * `sku`, `barcode` là của Biến thể mặc định. Cùng quy tắc trường với `CreateProductRequest`; không có Tồn kho, Giá vốn
 * (Giá vốn chỉ đổi qua nhập hàng, AD-15). Không có `Idempotency-Key`. Đổi giá bán thì backend ghi Nhật ký thao tác.
 * Sản phẩm nhiều Biến thể (SP-07): không gửi `salePrice`, `sku`, `barcode` (trống là không đổi); sửa từng Biến thể bằng
 * `UpdateVariantRequest`.
 */
export interface UpdateProductRequest {
  /** Bắt buộc, tối đa 200 ký tự. */
  name: string;
  /** Danh mục của Cửa hàng; bỏ trống thì Sản phẩm không thuộc Danh mục nào. */
  categoryId?: Uuid;
  /** Tối đa 30 ký tự; bỏ trống thì backend dùng "cái". */
  unit?: string;
  /** Tối đa 2000 ký tự, được xuống dòng; bỏ trống thì xóa. */
  description?: string;
  /** Bắt buộc với Sản phẩm một Biến thể, ≥ 0; Sản phẩm nhiều Biến thể bỏ trống (giữ nguyên). */
  salePrice?: Money;
  /** Tối đa 50 ký tự; bỏ trống thì giữ SKU hiện tại. */
  sku?: string;
  /** Tối đa 50 ký tự, chỉ chữ cái không dấu và chữ số; bỏ trống thì xóa (Sản phẩm nhiều Biến thể: giữ nguyên). */
  barcode?: string;
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

/** Nhà cung cấp rút gọn, như `SupplierLookup.SupplierSummary` của backend (Phiếu nhập KHO-03). */
export interface SupplierSummary {
  supplierId: Uuid;
  /** Mã chữ HOA, duy nhất trong một Cửa hàng; `null` khi Nhà cung cấp không có mã. */
  code: string | null;
  name: string;
}

/**
 * Nhà cung cấp, từ `GET /api/v1/catalog/suppliers` (có tìm kiếm), `GET …/{id}`, `POST`, `PUT` (FR-23, KHO-02). Cả
 * hai vai trò xem được; thêm, sửa chỉ Chủ cửa hàng. Không có xóa, công nợ, lịch sử nhập theo Nhà cung cấp.
 */
export interface Supplier extends SupplierSummary {
  /** Như đã nhập (sau chuẩn hóa); `null` khi bỏ trống. */
  phone: string | null;
  /** `null` khi bỏ trống. */
  address: string | null;
  /** `null` khi bỏ trống. */
  note: string | null;
}

/**
 * Body của `POST /api/v1/catalog/suppliers` và `PUT /api/v1/catalog/suppliers/{id}` (PUT thay toàn bộ: trường tùy
 * chọn thiếu thì xóa, kể cả mã). Không có `Idempotency-Key`.
 */
export interface SupplierRequest {
  /** Bắt buộc, tối đa 200 ký tự. Trùng tên được phép. */
  name: string;
  /**
   * Tối đa 30 ký tự: chữ cái không dấu, chữ số, `-`, `_`, bắt đầu bằng chữ cái hoặc chữ số; backend lưu chữ HOA. Trùng
   * mã: `409 supplier-code-taken`.
   */
  code?: string;
  /** Tối đa 20 ký tự: chữ số, khoảng trắng, `+ . - ( )`, phải có chữ số. */
  phone?: string;
  /** Tối đa 300 ký tự. */
  address?: string;
  /** Tối đa 500 ký tự, được xuống dòng. */
  note?: string;
}
