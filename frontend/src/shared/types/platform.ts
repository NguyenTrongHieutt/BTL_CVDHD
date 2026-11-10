/**
 * Kiểu dữ liệu của module `platform`: tài khoản, phiên đăng nhập, Cửa hàng.
 * Người giữ: Hiếu (B). Khớp với `platform/internal/auth/AuthController.java`, `StoreRegistrationController.java` và
 * `platform/internal/store/StoreController.java`.
 */
import type { Money, Uuid } from './common';

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

/**
 * Thông tin Cửa hàng (FR-48, TK-05): phản hồi của `GET` và `PUT /api/v1/platform/store`, chỉ Chủ cửa hàng.
 * Khớp với `platform/internal/store/StoreView.java`. Trường văn bản không bắt buộc là `null` khi bỏ trống; ba trường
 * ngân hàng cùng `null` khi chưa cài tài khoản nhận chuyển khoản (màn thanh toán ẩn VietQR, AD-22).
 */
export interface StoreInfo {
  /** Bắt buộc, tối đa 200 ký tự. */
  name: string;
  /** Tối đa 20 ký tự: chữ số, khoảng trắng, `+`, `.`, `-`. */
  phone: string | null;
  /** Tối đa 300 ký tự. */
  address: string | null;
  /** Dòng cuối hóa đơn, tối đa 300 ký tự. */
  invoiceFooter: string | null;
  /** Mã BIN NAPAS của ngân hàng, đúng 6 chữ số. */
  bankBin: string | null;
  /** 6–19 chữ số. */
  bankAccountNo: string | null;
  /** Chữ in hoa không dấu và khoảng trắng, tối đa 50 ký tự. */
  bankAccountName: string | null;
  /** Số tiền (đồng) để được 1 điểm, > 0. */
  loyaltyEarnAmount: Money;
  /** Giá trị (đồng) của 1 điểm khi đổi, ≥ 0. */
  loyaltyPointValue: Money;
  /** Cảnh báo lô hàng sắp hết hạn trước bấy nhiêu ngày, 0–365. */
  expiryWarningDays: number;
}

/**
 * Body của `PUT /api/v1/platform/store`: thay toàn bộ, trường bỏ trống thì xóa giá trị cũ. Ba trường ngân hàng cùng
 * trống (tắt chuyển khoản) hoặc cùng có. Không có `Idempotency-Key` (ghi đè, gửi lại không đổi gì).
 */
export type UpdateStoreRequest = StoreInfo;
