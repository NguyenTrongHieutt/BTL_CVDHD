/**
 * Phiên đăng nhập ở frontend (AD-9). Người giữ: Anh (shared), API của Hiếu (platform).
 *
 * Cookie `access_token` là HttpOnly nên JavaScript không đọc được token. Frontend biết ai đang
 * đăng nhập qua `GET /api/v1/platform/me`, giữ trong TanStack Query với khóa `['platform', 'me']`.
 * Đăng nhập hoặc đăng xuất thì làm mới khóa này. `role` chỉ dùng để ẩn menu và route.
 */
import { createContext, useContext, useRef } from 'react';
import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import type { LoginRequest, Me, RegisterStoreRequest } from '../types/platform';
import { api, newIdempotencyKey, shouldReuseIdempotencyKey } from './client';

export const ME_PATH = '/api/v1/platform/me';
export const LOGIN_PATH = '/api/v1/platform/auth/login';
export const LOGOUT_PATH = '/api/v1/platform/auth/logout';
export const REGISTER_STORE_PATH = '/api/v1/platform/stores';

export const meQueryKey = ['platform', 'me'] as const;

export function fetchMe(signal?: AbortSignal): Promise<Me> {
  return api.get<Me>(ME_PATH, { signal });
}

export const meQueryOptions = queryOptions({
  queryKey: meQueryKey,
  queryFn: ({ signal }) => fetchMe(signal),
  staleTime: 5 * 60_000,
});

/** Trạng thái `GET /me`. Dùng ở chỗ cần biết đã đăng nhập hay chưa (RequireAuth). */
export function useMe() {
  return useQuery(meQueryOptions);
}

/** Người dùng hiện tại; `RequireAuth` cung cấp giá trị này cho mọi màn đã đăng nhập. */
export const CurrentUserContext = createContext<Me | null>(null);

/** Người dùng đang đăng nhập. Chỉ gọi bên trong vùng `RequireAuth`. */
export function useCurrentUser(): Me {
  const me = useContext(CurrentUserContext);
  if (!me) throw new Error('useCurrentUser() chỉ dùng được bên trong RequireAuth');
  return me;
}

/**
 * Đăng nhập. 401 ở đây là sai thông tin đăng nhập, nên không chạy xử lý 401 chung.
 * Thành công thì xóa cache của phiên trước rồi đặt `Me` mới.
 */
export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: LoginRequest) =>
      api.post<Me>(LOGIN_PATH, { ...request, loginKey: request.loginKey.trim() }, { authRedirect: false }),
    onSuccess: (me) => {
      queryClient.clear();
      queryClient.setQueryData(meQueryKey, me);
    },
  });
}

/**
 * Đăng ký Cửa hàng (FR-43, TK-00). Backend tạo Cửa hàng cùng tài khoản Chủ cửa hàng và đặt cookie phiên,
 * nên thành công thì làm như đăng nhập: xóa cache của phiên trước rồi đặt `Me` mới.
 *
 * Giữ một `Idempotency-Key` qua các lần gửi lại cho tới khi thành công (AD-7). Sau lỗi mà
 * `shouldReuseIdempotencyKey` trả `false` (dữ liệu sai, số điện thoại đã dùng…) thì lần gửi sau dùng khóa mới.
 */
export function useRegisterStore() {
  const queryClient = useQueryClient();
  const idempotencyKey = useRef<string | null>(null);
  return useMutation({
    mutationFn: (request: RegisterStoreRequest) => {
      idempotencyKey.current ??= newIdempotencyKey();
      const body: RegisterStoreRequest = {
        storeName: request.storeName.trim(),
        loginKey: request.loginKey.trim(),
        password: request.password,
        displayName: request.displayName.trim(),
      };
      return api.post<Me>(REGISTER_STORE_PATH, body, { idempotencyKey: idempotencyKey.current, authRedirect: false });
    },
    onSuccess: (me) => {
      idempotencyKey.current = null;
      queryClient.clear();
      queryClient.setQueryData(meQueryKey, me);
    },
    onError: (error) => {
      if (!shouldReuseIdempotencyKey(error)) idempotencyKey.current = null;
    },
  });
}

/** Đăng xuất: backend xóa cookie, frontend chuyển về `/login` rồi xóa cache. */
export function useLogout() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () => api.post<void>(LOGOUT_PATH),
    onSuccess: async () => {
      await navigate('/login', { replace: true });
      queryClient.clear();
    },
  });
}
