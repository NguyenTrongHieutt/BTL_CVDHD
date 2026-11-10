/**
 * Kiểu dữ liệu của module `platform`: tài khoản, phiên đăng nhập, Cửa hàng.
 * Người giữ: Hiếu (B). Khớp với `platform/internal/auth/AuthController.java` và `StoreRegistrationController.java`.
 */
import type { Uuid } from './common';

/** Vai trò trong một Cửa hàng (PRD 4.7). Frontend chỉ dùng để ẩn menu và route; backend mới là nơi kiểm quyền (AD-16). */
export type Role = 'OWNER' | 'STAFF';

export const ROLE_LABELS: Readonly<Record<Role, string>> = {
  OWNER: 'Chủ cửa hàng',
  STAFF: 'Nhân viên',
};

/** Phản hồi của `GET /api/v1/platform/me` và `POST /api/v1/platform/auth/login` (AD-9). */
export interface Me {
  userId: Uuid;
  storeId: Uuid;
  storeName: string;
  role: Role;
  displayName: string;
}

/** Body của `POST /api/v1/platform/auth/login`. `loginKey`: số điện thoại hoặc email (Chủ cửa hàng), tên đăng nhập (Nhân viên). */
export interface LoginRequest {
  loginKey: string;
  password: string;
}

/**
 * Body của `POST /api/v1/platform/stores` (FR-43, TK-00), kèm header `Idempotency-Key`.
 * Thành công trả `Me` của Chủ cửa hàng mới và đặt cookie phiên như đăng nhập.
 * `loginKey`: số điện thoại (`0` + 9 chữ số hoặc `+84` + 9 chữ số) hoặc email; `password`: 8–72 ký tự.
 */
export interface RegisterStoreRequest {
  storeName: string;
  loginKey: string;
  password: string;
  displayName: string;
}

// TODO(Hiếu, FR-48/TK-05, GĐ1): kiểu thông tin Cửa hàng (địa chỉ, số điện thoại in hóa đơn, tài khoản VietQR).
