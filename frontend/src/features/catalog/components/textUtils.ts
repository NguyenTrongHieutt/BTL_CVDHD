/**
 * Xử lý chữ dùng chung trong features/catalog (form thêm Sản phẩm, bộ lọc Sản phẩm). Người giữ: Hiếu (AD-20).
 */

/** Tiền lớn nhất backend nhận: số nguyên lớn nhất JavaScript còn biểu diễn đúng (9 007 199 254 740 991). */
export const MONEY_MAX = Number.MAX_SAFE_INTEGER;

/** Chữ thường, bỏ dấu, để tìm trong danh sách lựa chọn (Danh mục) không cần gõ dấu. */
export function foldText(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').replace(/[đĐ]/g, 'd').toLowerCase();
}

/**
 * Đọc ô số: chữ số, dấu chấm nhóm nghìn. Trống thì `null`; có ký tự khác (dấu phẩy, dấu trừ, chữ…) thì `NaN`.
 */
export function parseWholeNumber(text: string | null | undefined): number | null {
  const trimmed = (text ?? '').trim();
  if (trimmed === '') return null;
  const digits = trimmed.replace(/\./g, '');
  return /^\d+$/.test(digits) ? Number(digits) : Number.NaN;
}

/** Tự nhóm nghìn khi gõ ("1500000" → "1.500.000"); chuỗi có ký tự khác để nguyên cho luật kiểm báo lỗi. */
export function groupThousands(value: string | undefined): string | undefined {
  if (value === undefined) return value;
  const digits = value.trim().replace(/\./g, '');
  if (!/^\d+$/.test(digits)) return value;
  return BigInt(digits).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}
