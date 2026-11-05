import { beforeEach, describe, expect, it } from 'vitest';
import { cartStorageKey, clearCart, loadCart, saveCart } from './cartStorage';

beforeEach(() => {
  localStorage.clear();
});

describe('giỏ hàng ở localStorage (Consistency Conventions, dòng "Giỏ hàng")', () => {
  it('khóa là cart:<storeId>:<userId>', () => {
    expect(cartStorageKey('s1', 'u1')).toBe('cart:s1:u1');
  });

  it('lưu và đọc lại giỏ cùng Idempotency-Key', () => {
    const cart = loadCart('s1', 'u1');
    saveCart('s1', 'u1', { ...cart, lines: [{ variantId: 'v1', quantity: 2 }] });

    const reloaded = loadCart('s1', 'u1');
    expect(reloaded.lines).toEqual([{ variantId: 'v1', quantity: 2 }]);
    expect(reloaded.idempotencyKey).toBe(cart.idempotencyKey);
    expect(loadCart('s1', 'u2').lines).toEqual([]);
  });

  it('xóa giỏ thì sinh Idempotency-Key mới (AD-7)', () => {
    const cart = loadCart('s1', 'u1');
    saveCart('s1', 'u1', cart);
    const cleared = clearCart('s1', 'u1');
    expect(cleared.lines).toEqual([]);
    expect(cleared.idempotencyKey).not.toBe(cart.idempotencyKey);
  });

  it('dữ liệu hỏng thì bắt đầu giỏ mới', () => {
    localStorage.setItem(cartStorageKey('s1', 'u1'), '{không phải json');
    expect(loadCart('s1', 'u1').lines).toEqual([]);
  });
});
