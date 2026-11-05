/**
 * Định dạng tiền VND. Người giữ: Anh.
 * Chỉ định dạng, không tính toán: mọi số tiền đến từ backend dưới dạng số nguyên đồng (AD-8).
 */
import type { Money } from '../types/common';

const vndFormatter = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
});

export const EMPTY_MONEY = '—';

/** "1.234.000 ₫" (giữa số và ký hiệu là khoảng trắng không ngắt dòng U+00A0). */
export function formatMoney(amount: Money | null | undefined): string {
  if (amount === null || amount === undefined || !Number.isFinite(amount)) return EMPTY_MONEY;
  if (import.meta.env.DEV && !Number.isSafeInteger(amount)) {
    console.warn(`MoneyText: ${amount} không phải số nguyên đồng; tiền phải do backend tính (AD-8).`);
  }
  return vndFormatter.format(amount);
}
