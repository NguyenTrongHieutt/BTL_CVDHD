/**
 * Xử lý chữ dùng chung trong features/catalog (form Sản phẩm, Khách hàng, bộ lọc Sản phẩm). Người giữ: Hiếu (AD-20).
 */

/** Tiền lớn nhất backend nhận: số nguyên lớn nhất JavaScript còn biểu diễn đúng (9 007 199 254 740 991). */
export const MONEY_MAX = Number.MAX_SAFE_INTEGER;

const ZWNJ = 0x200c;
const ZWJ = 0x200d;

/**
 * Chuẩn hóa chuỗi như backend (`TextFields`): bỏ ký tự định dạng vô hình (trường nhiều dòng giữ ZWNJ, ZWJ của emoji
 * ghép), đổi khoảng trắng Unicode loại Zs thành dấu cách thường, NFC, cắt khoảng trắng hai đầu; trường nhiều dòng
 * đổi `\r\n`, `\r` thành `\n`. Dùng trước khi kiểm độ dài và trước khi gửi.
 */
export function normalizeText(value: string, multiline = false): string {
  const text = value
    .replace(/\p{Cf}/gu, (c) => {
      const code = c.codePointAt(0);
      return multiline && (code === ZWNJ || code === ZWJ) ? c : '';
    })
    .replace(/\p{Zs}/gu, ' ')
    .normalize('NFC');
  return (multiline ? text.replace(/\r\n?/g, '\n') : text).trim();
}

/** Số ký tự đếm như backend: theo code point (emoji là một ký tự), sau khi chuẩn hóa. */
export function textLength(value: string, multiline = false): number {
  return [...normalizeText(value, multiline)].length;
}

/**
 * Ký tự backend từ chối (sau khi chuẩn hóa): ký tự điều khiển (trường nhiều dòng cho phép xuống dòng, tab),
 * surrogate lẻ, và ở trường một dòng cả ngắt dòng, ngắt đoạn Unicode (U+2028, U+2029).
 */
export function hasInvalidChars(value: string, multiline = false): boolean {
  return [...normalizeText(value, multiline)].some((c) => {
    if (/\p{Cs}/u.test(c)) return true;
    if (/[\p{Zl}\p{Zp}]/u.test(c)) return !multiline;
    return /\p{Cc}/u.test(c) && !(multiline && (c === '\n' || c === '\t'));
  });
}

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
