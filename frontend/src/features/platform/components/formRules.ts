/**
 * Rule antd dùng chung cho các form của platform (Nhân viên TK-02, Thông tin cá nhân TK-04). Người giữ: Hiếu —
 * features/platform (AD-20).
 */
import { isApiError } from '../../../shared/api/client';

/**
 * Lỗi `errors[]` của backend đặt được hết dưới các ô có tên trong `fields`; `null` nếu không có lỗi theo trường hoặc
 * có phần không thuộc ô nào (khi đó hiện khung báo lỗi chung).
 */
export function fieldErrorsOf<F extends string>(
  error: unknown,
  fields: readonly F[],
): { name: F; errors: string[] }[] | null {
  if (!isApiError(error) || error.errors.length === 0) return null;
  const isField = (field: string): field is F => (fields as readonly string[]).includes(field);
  const byField = new Map<F, string[]>();
  for (const { field, message } of error.errors) {
    if (!isField(field)) return null;
    byField.set(field, [...(byField.get(field) ?? []), message]);
  }
  return [...byField].map(([name, errors]) => ({ name, errors }));
}

/** Rule antd từ một hàm kiểm trả câu lỗi (giống backend). */
export function checkWith(check: (value: string | undefined) => string | null) {
  return {
    validator: (_: unknown, value?: string) => {
      const error = check(value);
      return error ? Promise.reject(new Error(error)) : Promise.resolve();
    },
  };
}

/** Ô "Nhập lại mật khẩu" phải khớp ô `field`. */
export function sameAs(field: string) {
  return ({ getFieldValue }: { getFieldValue: (name: string) => unknown }) => ({
    validator: (_: unknown, value?: string) =>
      !value || getFieldValue(field) === value
        ? Promise.resolve()
        : Promise.reject(new Error('Mật khẩu nhập lại không khớp')),
  });
}
