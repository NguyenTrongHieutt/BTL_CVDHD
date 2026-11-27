import { useRef } from 'react';
import { newIdempotencyKey, shouldReuseIdempotencyKey } from './client';

/**
 * Giữ nguyên danh tính của lần ghi qua lỗi mơ hồ (mất mạng, 5xx, hết thời gian chờ worker).
 * Khóa chỉ được bỏ sau thành công hoặc lỗi dứt khoát; backend sẽ từ chối nếu cùng khóa bị gửi với nội dung khác,
 * nhờ vậy một thao tác cũ còn chạy không thể vô tình biến thành lần ghi thứ hai.
 */
export function useStableIdempotencyKey() {
  const active = useRef<{ identity: string; key: string } | undefined>(undefined);

  return {
    keyFor(identity: unknown): string {
      const serialized = JSON.stringify(identity) ?? 'undefined';
      if (!active.current) {
        active.current = { identity: serialized, key: newIdempotencyKey() };
      }
      return active.current.key;
    },
    succeeded(): void {
      active.current = undefined;
    },
    failed(error: unknown): void {
      if (!shouldReuseIdempotencyKey(error)) active.current = undefined;
    },
  };
}
