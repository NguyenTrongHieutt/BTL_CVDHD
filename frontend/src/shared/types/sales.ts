/**
 * Kiểu dữ liệu của module `sales`: tính tiền, Đơn hàng, giỏ hàng, hàng bán được.
 * Người giữ: Anh (A). Màn FR-20 của Hiếu (features/catalog) dùng kiểu Đơn hàng ở đây (AD-20).
 *
 * Quy tắc AD-8: request chỉ gửi `variantId`, số lượng và ý định giảm giá. Mọi số tiền (đơn giá,
 * giảm giá, `netLineAmount`, tổng, tiền thừa) do backend tính ở `POST /api/v1/sales/orders/quote`;
 * frontend chỉ hiển thị.
 */
import type { IsoDateTime, Money, Quantity, Uuid } from './common';

export type PaymentMethod = 'CASH' | 'TRANSFER';

/** Trạng thái Đơn hàng (FR-15). `CANCELLED` và `RETURNED` là trạng thái cuối. */
export type OrderStatus = 'COMPLETED' | 'CANCELLED' | 'RETURNED';

/** Ý định giảm giá (FR-10). `AMOUNT`: số đồng; `PERCENT`: 0–100, backend làm tròn half-up về đồng. */
export interface DiscountIntent {
  kind: 'AMOUNT' | 'PERCENT';
  value: number;
}

export interface QuoteLineRequest {
  variantId: Uuid;
  quantity: Quantity;
  discount?: DiscountIntent;
}

/** Body của `POST /api/v1/sales/orders/quote` (AD-8). Frontend gọi lại mỗi khi giỏ đổi, debounce 300 ms. */
export interface QuoteRequest {
  lines: QuoteLineRequest[];
  orderDiscount?: DiscountIntent;
  customerId?: Uuid;
  paymentMethod: PaymentMethod;
  /** Tiền khách đưa (tiền mặt); backend trả lại `changeAmount` (FR-11). */
  cashReceived?: Money;
  // TODO(Anh, DH-03/DH-04): chốt ở CB-03 cách gửi Idempotency-Key của lần thanh toán khi gọi quote,
  // vì mã chuyển khoản trong `vietqrPayload` lấy từ khóa này (AD-22).
}

/** Một dòng đã tính tiền. */
export interface QuoteLine {
  variantId: Uuid;
  quantity: Quantity;
  unitPrice: Money;
  discountAmount: Money;
  netLineAmount: Money;
}

/** Phản hồi của `quote`. Màn bán hàng chỉ hiện các số ở đây (FR-11). */
export interface QuoteResponse {
  lines: QuoteLine[];
  subtotal: Money;
  discountTotal: Money;
  total: Money;
  /** Tiền thừa khi trả tiền mặt; `null` khi chưa nhập tiền khách đưa. */
  changeAmount: Money | null;
  /** Chuỗi VietQR (AD-22) khi chọn chuyển khoản; frontend chỉ vẽ bằng `QRCode` của antd. */
  vietqrPayload: string | null;
}

/** Body tạo Đơn hàng. Tổng tính lại khác `quotedTotal` thì backend trả `409 /errors/sales/cart-stale`. */
export interface CreateOrderRequest extends QuoteRequest {
  quotedTotal: Money;
}

/** Hàng bán được, từ `GET /api/v1/sales/sellable?ids=` (gộp `catalog` và `inventory`). */
export interface SellableItem {
  productId: Uuid;
  variantId: Uuid;
  name: string;
  variantName: string | null;
  barcode: string | null;
  unitPrice: Money;
  stockQuantity: Quantity;
  /** Dưới Ngưỡng tồn tối thiểu (FR-26). */
  lowStock: boolean;
}

/**
 * Giỏ hàng lưu ở `localStorage` theo khóa `cart:<storeId>:<userId>`, kèm `Idempotency-Key`
 * (Consistency Conventions, dòng "Giỏ hàng"). Giỏ không lưu giá: giá luôn lấy lại từ `quote`.
 */
export interface StoredCart {
  lines: QuoteLineRequest[];
  orderDiscount?: DiscountIntent;
  customerId?: Uuid;
  idempotencyKey: string;
  updatedAt: IsoDateTime;
}
