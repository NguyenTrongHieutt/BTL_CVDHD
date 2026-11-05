/**
 * Sinh UUID v4 cho `X-Request-Id` và `Idempotency-Key`. Người giữ: Anh.
 *
 * `crypto.randomUUID()` chỉ có trong secure context (HTTPS hoặc localhost). Khi mở bản demo
 * qua địa chỉ LAN bằng HTTP (ví dụ thử màn của Chủ cửa hàng trên điện thoại), hàm này dùng
 * `crypto.getRandomValues()`, vốn có ở mọi context.
 */
export function randomUuid(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  return uuidFromRandomBytes(webCrypto.getRandomValues(new Uint8Array(16)));
}

/** Dựng chuỗi UUID v4 từ 16 byte ngẫu nhiên (RFC 9562). */
export function uuidFromRandomBytes(bytes: Uint8Array): string {
  if (bytes.length !== 16) {
    throw new RangeError('UUID cần đúng 16 byte');
  }
  const b = Uint8Array.from(bytes);
  b[6] = (b[6] & 0x0f) | 0x40; // version 4
  b[8] = (b[8] & 0x3f) | 0x80; // variant RFC 9562
  const hex = Array.from(b, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
