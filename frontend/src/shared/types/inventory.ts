/**
 * Kiểu dữ liệu của module `inventory`: Tồn kho, lịch sử xuất nhập, Phiếu nhập, Phiếu kiểm kho.
 * Người giữ: Anh (A). Màn FR-28 của Hiếu (features/catalog) dùng `StockLedgerEntry` (AD-20).
 */
import type { IsoDateTime, Money, Quantity, Uuid } from './common';

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
 * Một bản ghi lịch sử xuất nhập (FR-22, FR-28), từ `GET /api/v1/inventory/ledger`.
 * TODO(Anh, KHO-01, GĐ1): chốt tên trường và danh sách `documentType` ở CB-03.
 */
export interface StockLedgerEntry {
  occurredAt: IsoDateTime;
  /** Loại chứng từ, ví dụ `OPENING` (tồn đầu kỳ, AD-14). */
  documentType: string;
  documentId: Uuid;
  variantId: Uuid;
  /** Số lượng thay đổi: dương là cộng, âm là trừ. */
  quantityChange: Quantity;
  performedBy: string;
}
