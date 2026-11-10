/**
 * API tài khoản Nhân viên (FR-45, TK-02). Người giữ: Hiếu — features/platform (AD-20). Chỉ Chủ cửa hàng.
 *
 * Bảng dùng khóa `['platform', 'users', params]`. Tạo, khóa, mở khóa invalidate `['platform', 'users']` cả khi lỗi
 * (mất phản hồi có thể là đã ghi; `404`, `409` cho thấy bảng đang cũ). Đặt lại mật khẩu không đổi gì trong bảng, chỉ
 * tải lại khi lỗi.
 * Tài khoản không phải chứng từ nên không có `Idempotency-Key`.
 *
 * Các hàm kiểm ở đây làm giống backend (`platform/internal/user/StaffAccountService.java`), để lỗi được báo ngay dưới
 * ô trước khi gửi; backend vẫn kiểm lại.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type { Uuid } from '../../../shared/types/common';
import type { CreateStaffRequest, ResetPasswordRequest, StoreUser } from '../../../shared/types/platform';
import { hasInvalidStoreChars, normalizeStoreText, storeTextLength } from './store';

export const USERS_PATH = '/api/v1/platform/users';

export const usersQueryKey = ['platform', 'users'] as const;

export const LOGIN_TAKEN = '/errors/platform/login-taken';
export const OWNER_PROTECTED = '/errors/platform/owner-protected';

export const LOGIN_KEY_MIN = 3;
export const LOGIN_KEY_MAX = 50;
export const DISPLAY_NAME_MAX = 100;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 72;
/** Giới hạn của bcrypt tính theo byte UTF-8 (chữ có dấu tốn 2–3 byte). */
export const PASSWORD_MAX_BYTES = 72;
export const PHONE_MAX = 20;

const LOGIN_KEY_CHARS = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
/** Định danh của Chủ cửa hàng (`LoginKeys.OWNER_LOGIN_KEY` ở backend): số điện thoại hoặc email. */
const OWNER_SHAPED = /^(?:(?:0|\+84)[0-9]{9}|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})$/;
const PHONE_CHARS = /^[0-9 +.-]+$/;

export function fetchUsers(request: PageRequest, signal?: AbortSignal): Promise<PageResponse<StoreUser>> {
  return api.get<PageResponse<StoreUser>>(USERS_PATH, { query: pageRequestToQuery(request), signal });
}

/** Cắt hai đầu như `String.trim()` của Java (mọi ký tự ≤ U+0020), như backend làm với tên đăng nhập. */
function trimLikeJava(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && text.charCodeAt(start) <= 0x20) start++;
  while (end > start && text.charCodeAt(end - 1) <= 0x20) end--;
  return text.slice(start, end);
}

/** Tên đăng nhập gửi đi: cắt hai đầu, chữ thường (backend cũng lưu chữ thường). */
export function normalizeLoginKey(value: string | null | undefined): string {
  return trimLikeJava(value ?? '').toLowerCase();
}

/** Câu lỗi của tên đăng nhập Nhân viên, như backend; `null` nếu hợp lệ. */
export function loginKeyError(value: string | null | undefined): string | null {
  const key = trimLikeJava(value ?? '');
  if (key === '') return 'Nhập tên đăng nhập';
  if (key.includes('@') || OWNER_SHAPED.test(key)) {
    return 'Tên đăng nhập không được là số điện thoại hay email (dành cho Chủ cửa hàng)';
  }
  if (!LOGIN_KEY_CHARS.test(key)) {
    return 'Tên đăng nhập chỉ gồm chữ cái không dấu, chữ số và các dấu . _ -, bắt đầu bằng chữ cái hoặc chữ số';
  }
  if (key.length < LOGIN_KEY_MIN || key.length > LOGIN_KEY_MAX) {
    return `Tên đăng nhập từ ${LOGIN_KEY_MIN} đến ${LOGIN_KEY_MAX} ký tự`;
  }
  return null;
}

/** Câu lỗi của tên Nhân viên (chuẩn hóa như các tên khác), như backend; `null` nếu hợp lệ. */
export function displayNameError(value: string | null | undefined): string | null {
  if (hasInvalidStoreChars(value)) return 'Tên Nhân viên có ký tự không hợp lệ';
  if (normalizeStoreText(value) === '') return 'Nhập tên Nhân viên';
  if (storeTextLength(value) > DISPLAY_NAME_MAX) return `Tên Nhân viên tối đa ${DISPLAY_NAME_MAX} ký tự`;
  return null;
}

/**
 * Câu lỗi của mật khẩu, như backend; `null` nếu hợp lệ. Mật khẩu gửi nguyên (không cắt khoảng trắng), nhưng chỉ có
 * khoảng trắng là thiếu: màn đăng nhập (`@NotBlank`) không bao giờ nhận mật khẩu như vậy. Surrogate lẻ bị từ chối vì
 * backend mã hóa UTF-8 đổi nó thành `?`.
 */
export function passwordError(value: string | null | undefined): string | null {
  const password = value ?? '';
  if (trimLikeJava(password) === '') return 'Nhập mật khẩu';
  if (/\p{Cs}/u.test(password)) return 'Mật khẩu có ký tự không hợp lệ';
  const length = [...password].length;
  if (length < PASSWORD_MIN || length > PASSWORD_MAX) {
    return `Mật khẩu từ ${PASSWORD_MIN} đến ${PASSWORD_MAX} ký tự`;
  }
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    return 'Mật khẩu quá dài, hãy dùng mật khẩu ngắn hơn';
  }
  return null;
}

/** Câu lỗi của số điện thoại (tùy chọn, như Thông tin Cửa hàng); `null` nếu hợp lệ hoặc bỏ trống. */
export function staffPhoneError(value: string | null | undefined): string | null {
  const phone = normalizeStoreText(value);
  if (phone === '') return null;
  if (hasInvalidStoreChars(value) || !PHONE_CHARS.test(phone)) {
    return 'Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . -';
  }
  if (!/[0-9]/.test(phone)) return 'Số điện thoại phải có chữ số';
  if ([...phone].length > PHONE_MAX) return `Số điện thoại tối đa ${PHONE_MAX} ký tự`;
  return null;
}

/** Body gửi đi đã chuẩn hóa như backend; số điện thoại bỏ trống thì không gửi. */
export function createStaffBody(request: CreateStaffRequest): CreateStaffRequest {
  const phone = normalizeStoreText(request.phone);
  return {
    loginKey: normalizeLoginKey(request.loginKey),
    displayName: normalizeStoreText(request.displayName),
    password: request.password,
    ...(phone === '' ? {} : { phone }),
  };
}

function userPath(id: Uuid, action: 'lock' | 'unlock' | 'reset-password'): string {
  return `${USERS_PATH}/${encodeURIComponent(id)}/${action}`;
}

export function useCreateStaff() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateStaffRequest) => api.post<StoreUser>(USERS_PATH, createStaffBody(request)),
    // Cả khi lỗi: mất phản hồi có thể là máy chủ đã tạo rồi, bảng phải hiện tài khoản đó
    onSettled: () => queryClient.invalidateQueries({ queryKey: usersQueryKey }),
  });
}

/** Khóa (`lock`) hoặc mở khóa (`unlock`) một Nhân viên. */
export function useSetUserLocked() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, locked }: { userId: Uuid; locked: boolean }) =>
      api.post<StoreUser>(userPath(userId, locked ? 'lock' : 'unlock')),
    onSettled: () => queryClient.invalidateQueries({ queryKey: usersQueryKey }),
  });
}

export function useResetPassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, password }: { userId: Uuid; password: string }) =>
      api.post<void>(userPath(userId, 'reset-password'), { password } satisfies ResetPasswordRequest),
    // 404, 409: bảng đang cũ (tài khoản không còn, hoặc không phải Nhân viên), tải lại
    onError: () => queryClient.invalidateQueries({ queryKey: usersQueryKey }),
  });
}
