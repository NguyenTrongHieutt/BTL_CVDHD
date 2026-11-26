/**
 * Kiểu dữ liệu của module `inventory`: Tồn kho, lịch sử xuất nhập, Phiếu nhập, Phiếu kiểm kho.
 * Người giữ: Anh (A). Màn FR-28 của Hiếu (features/catalog) dùng `StockLedgerEntry` (AD-20).
 */
import type { IsoDate, IsoDateTime, Money, Quantity, Uuid } from './common';

/** Một dòng Tồn kho theo Biến thể (AD-6). */
export interface StockLevel {
  variantId: Uuid;
  quantity: Quantity;
  /** Ngưỡng tồn tối thiểu (FR-26); `null` khi chưa đặt. */
  minQuantity: Quantity | null;
  /** `false` khi Biến thể đã ngừng kinh doanh (AD-14). */
  isActive: boolean;
  /** Giá vốn bình quân. Chỉ có trong phản hồi cho Chủ cửa hàng; DTO cho Nhân viên không có trường này (AD-16). */
  avgUnitCost?: Money;
}

/**
 * Loại chứng từ làm thay đổi Tồn kho, như enum `StockDocumentType` của backend (`stock_ledger.document_type`):
 * Tồn đầu kỳ (AD-14), bán hàng, hủy Đơn hàng, trả hàng, Phiếu nhập, Phiếu kiểm kho.
 */
export type StockDocumentType = 'OPENING' | 'SALE' | 'SALE_CANCEL' | 'SALE_RETURN' | 'PURCHASE_RECEIPT' | 'STOCKTAKE';

/**
 * Một dòng sổ kho (FR-22, FR-28), từ `GET /api/v1/inventory/ledger` (CB-03 mục 3.3, KHO-01). Chỉ Chủ cửa hàng.
 * Sửa theo phản hồi thật ở KHO-07 (Hiếu sửa, Anh duyệt).
 */
export interface StockLedgerEntry {
  /** Thời điểm ghi sổ, ISO-8601 UTC. */
  createdAt: IsoDateTime;
  /** Loại chứng từ. Kiểu là `string` để màn hình vẫn hiện được loại mới mà frontend chưa biết. */
  documentType: StockDocumentType | (string & {});
  documentId: Uuid;
  variantId: Uuid;
  /** `null` khi Biến thể không còn (không tra được ở catalog). */
  productName: string | null;
  /** `null` với Biến thể mặc định, hoặc khi Biến thể không còn. */
  variantName: string | null;
  /** Số lượng thay đổi: dương là cộng, âm là trừ. */
  delta: Quantity;
  /** Tồn kho của Biến thể ngay sau lần thay đổi này. */
  quantityAfter: Quantity;
  /** Tên người thực hiện; `null` khi không có (ví dụ tài khoản không còn). */
  actorName: string | null;
}

/**
 * Bộ lọc của `GET /api/v1/inventory/ledger`. Trường bỏ trống thì không lọc. `fromDate`, `toDate` theo lịch Việt Nam,
 * gồm cả hai đầu, tối đa 366 ngày (AD-19; ngoài khoảng: `400 /errors/common/validation` trên `toDate`).
 * `actorId` chỉ dùng khi backend đã hỗ trợ lọc người thực hiện (FR-28).
 */
export interface StockLedgerSearchParams {
  actorId?: Uuid;
  variantId?: Uuid;
  documentType?: StockDocumentType;
  fromDate?: IsoDate;
  toDate?: IsoDate;
}
