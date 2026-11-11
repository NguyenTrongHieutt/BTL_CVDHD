/**
 * API Khách hàng (FR-18 KH-01, FR-19 KH-02). Người giữ: Hiếu — features/catalog (AD-20). Cả hai vai trò.
 *
 * Bảng dùng khóa `['catalog', 'customers', { q, page, size, sort }]`. Thêm, sửa xong (thành công hay lỗi) thì
 * invalidate `['catalog', 'customers']`: lỗi trùng số (`409`), không tìm thấy (`404`) cho thấy bảng đang cũ, mất phản
 * hồi có thể là máy chủ đã ghi rồi. Khách hàng không phải chứng từ nên không có `Idempotency-Key`: gửi lại cùng số bị
 * backend chặn bằng `409 customer-phone-taken` kèm `existingCustomerId`.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, isApiError } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type { Customer, CustomerRequest } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { normalizeText } from '../components/textUtils';

export const CUSTOMERS_PATH = '/api/v1/catalog/customers';

export const customersQueryKey = ['catalog', 'customers'] as const;

export const CUSTOMER_PHONE_TAKEN = '/errors/catalog/customer-phone-taken';

/** Câu lỗi của backend khi số điện thoại không đúng dạng. */
export const PHONE_INVALID = 'Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số';

const PHONE = /^(?:0|\+84)([0-9]{9})$/;
const PHONE_SEPARATORS = /[ .-]/g;

/**
 * Danh sách Khách hàng có tìm kiếm: tên chứa `q` (không dấu, không phân biệt hoa thường) hoặc số điện thoại chứa các
 * chữ số của `q`. `q` trống thì không gửi. Backend sắp theo tên không dấu (chưa có tên xếp sau), rồi số điện thoại.
 */
export function fetchCustomers(
  request: PageRequest,
  q: string | undefined,
  signal?: AbortSignal,
): Promise<PageResponse<Customer>> {
  return api.get<PageResponse<Customer>>(CUSTOMERS_PATH, {
    query: { ...pageRequestToQuery(request), q: q || undefined },
    signal,
  });
}

function customerPath(id: Uuid): string {
  return `${CUSTOMERS_PATH}/${encodeURIComponent(id)}`;
}

export function fetchCustomer(id: Uuid, signal?: AbortSignal): Promise<Customer> {
  return api.get<Customer>(customerPath(id), { signal });
}

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

/** Id Khách hàng đang dùng số điện thoại, khi lỗi là `409 customer-phone-taken`; ngược lại `null`. */
export function existingCustomerIdOf(error: unknown): Uuid | null {
  if (!isApiError(error) || error.type !== CUSTOMER_PHONE_TAKEN) return null;
  const id = error.extensions.existingCustomerId;
  return typeof id === 'string' && id !== '' ? id : null;
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
  return useMutation({
    mutationFn: (request: CustomerRequest) => api.post<Customer>(CUSTOMERS_PATH, customerBody(request)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: customersQueryKey }),
  });
}

export function useUpdateCustomer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, request }: { id: Uuid; request: CustomerRequest }) =>
      api.put<Customer>(customerPath(id), customerBody(request)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: customersQueryKey }),
  });
}
