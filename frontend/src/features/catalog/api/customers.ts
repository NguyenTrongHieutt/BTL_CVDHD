/**
 * API Khách hàng (FR-18 KH-01, FR-19 KH-02). Người giữ: Hiếu — features/catalog (AD-20). Cả hai vai trò.
 *
 * Bảng dùng khóa `['catalog', 'customers', { q, page, size, sort }]`. Thêm, sửa xong (thành công hay lỗi) thì
 * invalidate `['catalog', 'customers']`: lỗi trùng số (`409`), không tìm thấy (`404`) cho thấy bảng đang cũ, mất phản
 * hồi có thể là máy chủ đã ghi rồi. Mọi thao tác ghi mang `Idempotency-Key` để lần gửi lại tiếp tục đúng yêu cầu nền;
 * trùng số vẫn trả `409 customer-phone-taken` kèm `existingCustomerId`.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../shared/api/client';
import { useStableIdempotencyKey } from '../../../shared/api/useStableIdempotencyKey';
import { createCustomer, customerPath, customersQueryKey } from '../../../shared/api/customers';
import type { Customer, CustomerRequest } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { normalizeText } from '../components/textUtils';

export {
  CUSTOMERS_PATH, CUSTOMER_PHONE_TAKEN, customersQueryKey, existingCustomerIdOf, fetchCustomer, fetchCustomers,
} from '../../../shared/api/customers';

/** Câu lỗi của backend khi số điện thoại không đúng dạng. */
export const PHONE_INVALID = 'Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số';

const PHONE = /^(?:0|\+84)([0-9]{9})$/;
const PHONE_SEPARATORS = /[ .-]/g;

/**
 * Chuẩn hóa số điện thoại như backend: chuẩn hóa chuỗi như trường văn bản, bỏ dấu cách, dấu chấm, gạch ngang; `0` + 9
 * chữ số hoặc `+84` + 9 chữ số thành dạng `0…`. Không đúng dạng thì `null`.
 */
export function normalizePhone(value: string | undefined): string | null {
  const compact = normalizeText(value ?? '').replace(PHONE_SEPARATORS, '');
  const match = PHONE.exec(compact);
  return match ? `0${match[1]}` : null;
}

/** Số 10 chữ số hiện dạng "0912 345 678"; dạng khác để nguyên. */
export function formatPhone(phone: string): string {
  return /^[0-9]{10}$/.test(phone) ? `${phone.slice(0, 4)} ${phone.slice(4, 7)} ${phone.slice(7)}` : phone;
}

/** Tên để hiện trong câu thông báo: tên, hoặc số điện thoại khi chưa có tên. */
export function customerLabel(customer: Pick<Customer, 'name' | 'phone'>): string {
  return customer.name ?? formatPhone(customer.phone);
}

/** Body đã chuẩn hóa như backend; tên, ghi chú trống thì không gửi (PUT: xóa giá trị cũ). */
export function customerBody(request: CustomerRequest): CustomerRequest {
  const name = normalizeText(request.name ?? '');
  const note = normalizeText(request.note ?? '', true);
  return {
    phone: normalizePhone(request.phone) ?? request.phone.trim(),
    ...(name === '' ? {} : { name }),
    ...(note === '' ? {} : { note }),
  };
}

export function useCreateCustomer() {
  const queryClient = useQueryClient();
  const idempotency = useStableIdempotencyKey();
  return useMutation({
    mutationFn: (request: CustomerRequest) => {
      const body = customerBody(request);
      return createCustomer(body, idempotency.keyFor(body));
    },
    onSuccess: idempotency.succeeded,
    onError: idempotency.failed,
    onSettled: () => queryClient.invalidateQueries({ queryKey: customersQueryKey }),
  });
}

export function useUpdateCustomer() {
  const queryClient = useQueryClient();
  const idempotency = useStableIdempotencyKey();
  return useMutation({
    mutationFn: ({ id, request }: { id: Uuid; request: CustomerRequest }) => {
      const body = customerBody(request);
      return api.put<Customer>(customerPath(id), body, { idempotencyKey: idempotency.keyFor({ id, body }) });
    },
    onSuccess: idempotency.succeeded,
    onError: idempotency.failed,
    onSettled: () => queryClient.invalidateQueries({ queryKey: customersQueryKey }),
  });
}
