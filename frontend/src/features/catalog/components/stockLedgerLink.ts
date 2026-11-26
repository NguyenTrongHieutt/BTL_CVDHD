/**
 * Đường dẫn tới màn Lịch sử xuất nhập kho (KHO-07) lọc sẵn theo Sản phẩm và Biến thể. Người giữ: Hiếu —
 * features/catalog (AD-20). Dùng ở bảng Sản phẩm (Sản phẩm một Biến thể) và bảng Biến thể của form sửa (SP-07).
 */
import type { Uuid } from '../../../shared/types/common';

const STOCK_LEDGER_PATH = '/catalog/stock-ledger';

export function stockLedgerLink(productId: Uuid, variantId?: Uuid): string {
  const params = new URLSearchParams({ productId });
  if (variantId) params.set('variantId', variantId);
  return `${STOCK_LEDGER_PATH}?${params.toString()}`;
}
