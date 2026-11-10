import { describe, expect, it } from 'vitest';
import { ApiError, NETWORK_ERROR_TYPE } from './api/client';
import { PROBLEM_MESSAGES, describeError } from './messages.vi';

const error = (type: string, status: number, detail?: string) =>
  new ApiError({ type, title: 'x', status, detail, requestId: 'req-1' });

describe('messages.vi', () => {
  it.each([
    '/errors/common/idempotency-key-reuse',
    '/errors/common/payload-too-large',
    '/errors/platform/login-taken',
    '/errors/platform/owner-protected',
    '/errors/platform/wrong-current-password',
    '/errors/catalog/category-name-taken',
    '/errors/catalog/category-in-use',
    '/errors/catalog/barcode-taken',
    '/errors/catalog/sku-taken',
    '/errors/catalog/product-inactive',
    '/errors/catalog/image-type',
    '/errors/catalog/image-too-large',
    '/errors/catalog/customer-phone-taken',
    '/errors/catalog/supplier-code-taken',
    '/errors/sales/invalid-state',
    '/errors/sales/cart-stale',
    '/errors/inventory/variant-inactive',
    '/errors/ai/quota-exceeded',
  ])('có câu và hướng xử lý cho %s', (type) => {
    expect(PROBLEM_MESSAGES[type]?.message).toBeTruthy();
    expect(PROBLEM_MESSAGES[type]?.hint).toBeTruthy();
  });

  it('tra theo type trước, rồi theo mã trạng thái', () => {
    expect(describeError(error('/errors/sales/cart-stale', 409)).message).toBe(PROBLEM_MESSAGES['/errors/sales/cart-stale'].message);
    expect(describeError(error('/errors/sales/unknown', 403)).message).toBe('Bạn không có quyền thực hiện thao tác này.');
    expect(describeError(error('about:blank', 503)).message).toBe('Dịch vụ AI tạm thời chưa sẵn sàng.');
    expect(describeError(error('about:blank', 502)).message).toBe('Máy chủ gặp lỗi.');
    expect(describeError(error(NETWORK_ERROR_TYPE, 0)).message).toBe('Không kết nối được máy chủ.');
  });

  it('lỗi nghiệp vụ 422 chưa đăng ký thì dùng detail của backend', () => {
    expect(describeError(error('/errors/sales/return-exceeds', 422, 'Số lượng trả vượt số đã bán.')).message).toBe(
      'Số lượng trả vượt số đã bán.',
    );
  });

  it('kèm requestId và lỗi từng ô', () => {
    const described = describeError(
      new ApiError({
        type: '/errors/common/validation',
        title: 'x',
        status: 400,
        requestId: 'req-9',
        errors: [{ field: 'phone', message: 'không hợp lệ' }],
      }),
    );
    expect(described.requestId).toBe('req-9');
    expect(described.fieldErrors).toEqual([{ field: 'phone', message: 'không hợp lệ' }]);
  });

  it('lỗi không phải ApiError thì dùng câu chung', () => {
    expect(describeError(new Error('boom')).message).toBe('Đã có lỗi xảy ra.');
  });
});
