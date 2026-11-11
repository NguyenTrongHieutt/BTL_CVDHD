/**
 * API Thông tin cá nhân (FR-47, TK-04). Người giữ: Hiếu — features/platform (AD-20). Cả hai vai trò, luôn là chính
 * người đang đăng nhập (backend lấy từ token, không có id trong đường dẫn).
 *
 * Khóa `['platform', 'profile']`. Sửa hồ sơ thành công thì đặt hồ sơ backend trả về vào khóa này và invalidate
 * `['platform', 'me']` (tên ở thanh trên cùng, `Me.displayName`, đổi theo mà không cần đăng nhập lại) cùng
 * `['platform', 'users']` (dòng Chủ cửa hàng ở bảng Nhân viên). `PUT` ghi đè nên không có `Idempotency-Key`.
 *
 * Đổi mật khẩu: mật khẩu hiện tại sai là `422 /errors/platform/wrong-current-password`, không phải 401, nên client
 * không coi là hết phiên. Thành công (`204`) không đổi gì trong cache: phiên hiện tại vẫn dùng được.
 *
 * Các hàm kiểm ở đây làm giống backend (`platform/internal/user/AccountFields.java`), để lỗi được báo ngay dưới ô.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../shared/api/client';
import { ME_PATH, meQueryKey } from '../../../shared/api/session';
import type { ChangePasswordRequest, Profile, UpdateProfileRequest } from '../../../shared/types/platform';
import { normalizeStoreText } from './store';
import { PHONE_INVALID, normalizeVietnamPhone, usersQueryKey } from './users';

export const PROFILE_PATH = `${ME_PATH}/profile`;
export const CHANGE_PASSWORD_PATH = `${ME_PATH}/password`;

export const profileQueryKey = ['platform', 'profile'] as const;

export const WRONG_CURRENT_PASSWORD = '/errors/platform/wrong-current-password';

export function fetchProfile(signal?: AbortSignal): Promise<Profile> {
  return api.get<Profile>(PROFILE_PATH, { signal });
}

export function useProfile() {
  return useQuery({ queryKey: profileQueryKey, queryFn: ({ signal }) => fetchProfile(signal) });
}

/**
 * Câu lỗi của số điện thoại liên hệ (tùy chọn, số Việt Nam); `null` nếu hợp lệ hoặc bỏ trống. `stored` là số đang lưu:
 * số cũ không theo quy tắc này (ví dụ số bàn của Nhân viên tạo trước TK-04) gửi lại y nguyên thì được, như backend.
 */
export function profilePhoneError(value: string | null | undefined, stored: string | null = null): string | null {
  if (stored !== null && normalizeStoreText(value) === stored) return null;
  return normalizeVietnamPhone(value) === null ? PHONE_INVALID : null;
}

/** Body gửi đi đã chuẩn hóa như backend; số bỏ trống là `null` (xóa số đang lưu). */
export function updateProfileBody(request: UpdateProfileRequest): UpdateProfileRequest {
  const phone = normalizeVietnamPhone(request.phone);
  return {
    displayName: normalizeStoreText(request.displayName),
    // Số không theo quy tắc gửi nguyên: số cũ đang lưu thì backend giữ, số sai thì backend báo lỗi dưới ô
    phone: phone === '' ? null : (phone ?? request.phone),
  };
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateProfileRequest) => api.put<Profile>(ME_PATH, updateProfileBody(request)),
    onSuccess: async (profile) => {
      queryClient.setQueryData(profileQueryKey, profile);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: meQueryKey }),
        queryClient.invalidateQueries({ queryKey: usersQueryKey }),
      ]);
    },
  });
}

/** Mật khẩu gửi nguyên, không cắt khoảng trắng (như lúc đăng nhập). */
export function useChangePassword() {
  return useMutation({
    mutationFn: (request: ChangePasswordRequest) => api.post<void>(CHANGE_PASSWORD_PATH, request),
  });
}
