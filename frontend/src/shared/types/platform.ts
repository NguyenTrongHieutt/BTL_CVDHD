/**
 * Kiểu dữ liệu của module `platform`: tài khoản, phiên đăng nhập, Cửa hàng.
 * Người giữ: Hiếu (B). Khớp với `platform/internal/auth/AuthController.java`.
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

// TODO(Hiếu, FR-43/TK-00, GĐ1): kiểu đăng ký Cửa hàng (tên Cửa hàng, số điện thoại hoặc email, mật khẩu).
// TODO(Hiếu, FR-48/TK-05, GĐ1): kiểu thông tin Cửa hàng (địa chỉ, số điện thoại in hóa đơn, tài khoản VietQR).
