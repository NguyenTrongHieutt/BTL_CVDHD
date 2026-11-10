/**
 * API Nhà cung cấp (FR-23, KHO-02). Người giữ: Hiếu — features/catalog (AD-20). Xem: cả hai vai trò; thêm, sửa: chỉ
 * Chủ cửa hàng.
 *
 * Bảng dùng khóa `['catalog', 'suppliers', { q, page, size, sort }]`. Thêm, sửa xong (thành công hay lỗi) thì
 * invalidate `['catalog', 'suppliers']`: lỗi trùng mã (`409`), không tìm thấy (`404`) cho thấy bảng đang cũ, mất phản
 * hồi có thể là máy chủ đã ghi rồi. Nhà cung cấp không phải chứng từ nên không có `Idempotency-Key`: gửi lại cùng mã
 * bị backend chặn bằng `409 supplier-code-taken`.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type { Supplier, SupplierRequest } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { normalizeText } from '../components/textUtils';

export const SUPPLIERS_PATH = '/api/v1/catalog/suppliers';

export const suppliersQueryKey = ['catalog', 'suppliers'] as const;

export const SUPPLIER_CODE_TAKEN = '/errors/catalog/supplier-code-taken';

/** Câu lỗi của backend khi mã có ký tự ngoài chữ cái không dấu, chữ số, `-`, `_`, hoặc không bắt đầu đúng. */
export const CODE_INVALID =
  'Mã Nhà cung cấp chỉ gồm chữ cái không dấu, chữ số, dấu - và dấu _, bắt đầu bằng chữ cái hoặc chữ số';
/** Câu lỗi của backend khi số điện thoại có ký tự khác chữ số, khoảng trắng, `+ . - ( )`. */
export const PHONE_CHARS_INVALID = 'Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . - ( )';
/** Câu lỗi của backend khi số điện thoại không có chữ số nào. */
export const PHONE_NO_DIGIT = 'Số điện thoại phải có chữ số';

/** Thứ tự "mới thêm trước" (`sort=createdAt,desc`), dùng ngay sau khi thêm Nhà cung cấp. */
export const NEWEST_FIRST = 'createdAt,desc';

const CODE_CHARS = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;
const PHONE_CHARS = /^[0-9 +.\-()]+$/;

/**
 * Danh sách Nhà cung cấp có tìm kiếm: tên chứa `q` (không dấu, không phân biệt hoa thường), mã bắt đầu bằng `q`, hoặc
 * số điện thoại chứa các chữ số của `q` (`84`, `+84` ở đầu hiểu như `0`). `q` trống thì không gửi. `request.sort`:
 * `name` (mặc định: tên không dấu, rồi mã) hoặc `createdAt`, kèm `asc` hay `desc`.
 */
export function fetchSuppliers(
  request: PageRequest,
  q: string | undefined,
  signal?: AbortSignal,
): Promise<PageResponse<Supplier>> {
  return api.get<PageResponse<Supplier>>(SUPPLIERS_PATH, {
    query: { ...pageRequestToQuery(request), q: q || undefined },
    signal,
  });
}

function supplierPath(id: Uuid): string {
  return `${SUPPLIERS_PATH}/${encodeURIComponent(id)}`;
}

/**
 * Mã đúng dạng (sau chuẩn hóa): chữ cái không dấu, chữ số, `-`, `_`, bắt đầu bằng chữ cái hoặc chữ số. Mã trống thì
 * đúng (không bắt buộc).
 */
export function isValidSupplierCode(code: string | undefined): boolean {
  const text = normalizeText(code ?? '');
  return text === '' || CODE_CHARS.test(text);
}

/**
 * Lỗi ký tự của số điện thoại như backend (như Thông tin Cửa hàng): chỉ chữ số, khoảng trắng, `+ . - ( )`, phải có
 * chữ số. Trống thì không lỗi (không bắt buộc); độ dài kiểm riêng.
 */
export function supplierPhoneError(phone: string | undefined): string | null {
  const text = normalizeText(phone ?? '');
  if (text === '') return null;
  if (!PHONE_CHARS.test(text)) return PHONE_CHARS_INVALID;
  return /[0-9]/.test(text) ? null : PHONE_NO_DIGIT;
}

/**
 * Body đã chuẩn hóa như backend: chữ chuẩn hóa như `TextFields`, mã viết HOA; trường tùy chọn trống thì không gửi
 * (PUT: xóa giá trị cũ).
 */
export function supplierBody(request: SupplierRequest): SupplierRequest {
  const code = normalizeText(request.code ?? '');
  const phone = normalizeText(request.phone ?? '');
  const address = normalizeText(request.address ?? '');
  const note = normalizeText(request.note ?? '', true);
  return {
    name: normalizeText(request.name),
    // Chỉ viết HOA mã đúng dạng (ASCII): mã sai để nguyên cho backend báo lỗi
    ...(code === '' ? {} : { code: CODE_CHARS.test(code) ? code.toUpperCase() : code }),
    ...(phone === '' ? {} : { phone }),
    ...(address === '' ? {} : { address }),
    ...(note === '' ? {} : { note }),
  };
}

export function useCreateSupplier() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: SupplierRequest) => api.post<Supplier>(SUPPLIERS_PATH, supplierBody(request)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: suppliersQueryKey }),
  });
}

export function useUpdateSupplier() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, request }: { id: Uuid; request: SupplierRequest }) =>
      api.put<Supplier>(supplierPath(id), supplierBody(request)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: suppliersQueryKey }),
  });
}
