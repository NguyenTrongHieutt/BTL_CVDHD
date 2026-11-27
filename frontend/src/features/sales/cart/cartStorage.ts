/**
 * Lưu giỏ hàng (FR-9). Người giữ: Anh — features/sales (AD-20).
 *
 * Theo Consistency Conventions, dòng "Giỏ hàng": giỏ nằm ở `localStorage` với khóa
 * `cart:<storeId>:<userId>`, kèm `Idempotency-Key` của lần thanh toán. Giỏ còn nguyên khi mất mạng
 * hoặc tải lại trang; server kiểm lại giá và trạng thái, giỏ đã cũ thì trả `409 /errors/sales/cart-stale`.
 * AD-7: thanh toán xong hoặc xóa giỏ thì sinh khóa mới.
 *
 * TODO(Anh, DH-01/DH-04): nối vào màn bán hàng; chỉ giữ khóa cũ khi `shouldReuseIdempotencyKey(error)`.
 */
import { newIdempotencyKey } from '../../../shared/api/client';
import { MAX_LOYALTY_POINTS } from '../../../shared/api/loyalty';
import type { StoredCart } from '../../../shared/types/sales';

export function cartStorageKey(storeId: string, userId: string): string {
  return `cart:${storeId}:${userId}`;
}

export function emptyCart(): StoredCart {
  return { lines: [], idempotencyKey: newIdempotencyKey(), updatedAt: new Date().toISOString() };
}

function storage(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

function isStoredCart(value: unknown): value is StoredCart {
  return (
    typeof value === 'object' &&
    value !== null &&
    'lines' in value &&
    Array.isArray(value.lines) &&
    'idempotencyKey' in value &&
    typeof value.idempotencyKey === 'string' &&
    value.idempotencyKey.length > 0
  );
}

export function loadCart(storeId: string, userId: string): StoredCart {
  try {
    const raw = storage()?.getItem(cartStorageKey(storeId, userId));
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    if (isStoredCart(parsed)) {
      const { pointsRedeemed, pendingPayment, ...stored } = parsed;
      const validPayment = pendingPayment && Array.isArray(pendingPayment.lines)
        && (pendingPayment.paymentMethod === 'CASH' || pendingPayment.paymentMethod === 'BANK_TRANSFER')
        && Number.isSafeInteger(pendingPayment.quotedTotal);
      const cart = validPayment ? { ...stored, pendingPayment } : stored;
      if (cart.customerId && typeof pointsRedeemed === 'number' && Number.isInteger(pointsRedeemed)
        && pointsRedeemed > 0 && pointsRedeemed <= MAX_LOYALTY_POINTS) {
        return { ...cart, pointsRedeemed };
      }
      return cart;
    }
  } catch {
    // Giỏ hỏng hoặc trình duyệt chặn localStorage: bắt đầu giỏ mới.
  }
  return emptyCart();
}

export function saveCart(storeId: string, userId: string, cart: StoredCart): void {
  try {
    storage()?.setItem(cartStorageKey(storeId, userId), JSON.stringify({ ...cart, updatedAt: new Date().toISOString() }));
  } catch {
    // Hết dung lượng hoặc bị chặn: giỏ vẫn còn trong bộ nhớ của màn hình.
  }
}

/** Xóa giỏ và trả giỏ rỗng với `Idempotency-Key` mới (AD-7). */
export function clearCart(storeId: string, userId: string): StoredCart {
  const cart = emptyCart();
  saveCart(storeId, userId, cart);
  return cart;
}
