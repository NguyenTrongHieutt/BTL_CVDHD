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
/** Câu lỗi khi số điện thoại không đúng dạng, như backend (`AccountFields.PHONE_INVALID`) và số Khách hàng (KH-01). */
export const PHONE_INVALID = 'Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số';

const LOGIN_KEY_CHARS = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
/** Định danh của Chủ cửa hàng (`LoginKeys.OWNER_LOGIN_KEY` ở backend): số điện thoại hoặc email. */
const VIETNAM_PHONE = /^(?:0|\+84)([0-9]{9})$/;
const PHONE_SEPARATORS = /[ .-]/g;
const OWNER_SHAPED = /^(?:(?:0|\+84)[0-9]{9}|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})$/;

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

/** "Tên Nhân viên" → "tên Nhân viên", cho câu "Nhập …" (như `AccountFields` ở backend). */
function lowerFirst(label: string): string {
  return label.charAt(0).toLowerCase() + label.slice(1);
}

/**
 * Câu lỗi của tên hiển thị (chuẩn hóa như các tên khác), như backend (`AccountFields.displayName`); `null` nếu hợp lệ.
 * `label` là tên ô trong câu lỗi: "Tên Nhân viên" (TK-02), "Tên hiển thị" (TK-04).
 */
export function displayNameError(value: string | null | undefined, label = 'Tên Nhân viên'): string | null {
  if (hasInvalidStoreChars(value)) return `${label} có ký tự không hợp lệ`;
  if (normalizeStoreText(value) === '') return `Nhập ${lowerFirst(label)}`;
  if (storeTextLength(value) > DISPLAY_NAME_MAX) return `${label} tối đa ${DISPLAY_NAME_MAX} ký tự`;
  return null;
}

/**
 * Câu lỗi của mật khẩu, như backend (`AccountFields.password`); `null` nếu hợp lệ. Mật khẩu gửi nguyên (không cắt
 * khoảng trắng), nhưng chỉ có khoảng trắng là thiếu: màn đăng nhập (`@NotBlank`) không bao giờ nhận mật khẩu như vậy.
 * Surrogate lẻ bị từ chối vì backend mã hóa UTF-8 đổi nó thành `?`. `label`: "Mật khẩu", "Mật khẩu mới".
 */
export function passwordError(value: string | null | undefined, label = 'Mật khẩu'): string | null {
  const password = value ?? '';
  if (trimLikeJava(password) === '') return `Nhập ${lowerFirst(label)}`;
  if (/\p{Cs}/u.test(password)) return `${label} có ký tự không hợp lệ`;
  const length = [...password].length;
  if (length < PASSWORD_MIN || length > PASSWORD_MAX) {
    return `${label} từ ${PASSWORD_MIN} đến ${PASSWORD_MAX} ký tự`;
  }
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    return `${label} quá dài, hãy dùng mật khẩu ngắn hơn`;
  }
  return null;
}

/** Chỉ có khoảng trắng (như `String.trim()` của Java) là trống: backend báo "thiếu" cho mật khẩu như vậy. */
export function isBlankPassword(value: string | null | undefined): boolean {
  return trimLikeJava(value ?? '') === '';
}

/**
 * Số điện thoại Việt Nam như backend lưu (`AccountFields.vietnamPhone`, dùng cho tài khoản Nhân viên và Thông tin cá
 * nhân): chuẩn hóa như trường văn bản, bỏ dấu cách, dấu chấm, gạch ngang; `0` + 9 chữ số hoặc `+84` + 9 chữ số thành
 * `0…`. Trống là `''`; sai dạng là `null`.
 */
export function normalizeVietnamPhone(value: string | null | undefined): string | null {
  const text = normalizeStoreText(value);
  if (text === '') return '';
  const match = VIETNAM_PHONE.exec(text.replace(PHONE_SEPARATORS, ''));
  return match ? `0${match[1]}` : null;
}

/** Câu lỗi của số điện thoại Nhân viên (tùy chọn, số Việt Nam); `null` nếu hợp lệ hoặc bỏ trống. */
export function staffPhoneError(value: string | null | undefined): string | null {
  return normalizeVietnamPhone(value) === null ? PHONE_INVALID : null;
}

/** Body gửi đi đã chuẩn hóa như backend; số điện thoại bỏ trống thì không gửi, số sai dạng gửi nguyên để backend báo. */
export function createStaffBody(request: CreateStaffRequest): CreateStaffRequest {
  const phone = normalizeVietnamPhone(request.phone);
  return {
    loginKey: normalizeLoginKey(request.loginKey),
    displayName: normalizeStoreText(request.displayName),
    password: request.password,
    ...(phone === '' ? {} : { phone: phone ?? request.phone }),
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
