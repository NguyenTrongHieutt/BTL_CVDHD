/**
 * Kiểu dữ liệu của module `platform`: tài khoản, phiên đăng nhập, Cửa hàng.
 * Người giữ: Hiếu (B). Khớp với `platform/internal/auth/AuthController.java`, `StoreRegistrationController.java`,
 * `platform/internal/store/StoreController.java`, `platform/internal/user/StaffAccountController.java` và
 * `platform/internal/user/ProfileController.java`.
 */
import type { IsoDateTime, Money, Uuid } from './common';

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

/** Trạng thái tài khoản: `LOCKED` thì không đăng nhập được, phiên đang mở bị chặn ở request kế tiếp (AD-9). */
export type UserStatus = 'ACTIVE' | 'LOCKED';

export const USER_STATUS_LABELS: Readonly<Record<UserStatus, string>> = {
  ACTIVE: 'Đang hoạt động',
  LOCKED: 'Đã khóa',
};

/**
 * Một tài khoản của Cửa hàng (FR-45, TK-02): dòng của `GET /api/v1/platform/users`, phản hồi của `POST` tạo Nhân viên
 * và của khóa, mở khóa. Khớp với `platform/internal/user/StoreUser.java`. Không có mật khẩu.
 */
export interface StoreUser {
  userId: Uuid;
  /** Tên đăng nhập (Nhân viên) hoặc số điện thoại, email (Chủ cửa hàng), chữ thường. */
  loginKey: string;
  displayName: string;
  role: Role;
  status: UserStatus;
  /** `null` khi bỏ trống. */
  phone: string | null;
  createdAt: IsoDateTime;
}

/**
 * Body của `POST /api/v1/platform/users` (chỉ Chủ cửa hàng). Không có `Idempotency-Key`.
 * `loginKey`: 3–50 ký tự `a-z 0-9 . _ -`, bắt đầu bằng chữ cái hoặc chữ số, không được có dạng số điện thoại hay email;
 * `displayName`: tối đa 100 ký tự; `password`: 8–72 ký tự, tối đa 72 byte; `phone`: tùy chọn, số Việt Nam `0` + 9 chữ số
 * hoặc `+84` + 9 chữ số, lưu dạng `0…`.
 */
export interface CreateStaffRequest {
  loginKey: string;
  displayName: string;
  password: string;
  phone?: string;
}

/** Body của `POST /api/v1/platform/users/{id}/reset-password`: mật khẩu mới, 8–72 ký tự; không gửi mật khẩu cũ. */
export interface ResetPasswordRequest {
  password: string;
}

/**
 * Thông tin cá nhân của người đang đăng nhập (FR-47, TK-04): phản hồi của `GET /api/v1/platform/me/profile` và
 * `PUT /api/v1/platform/me`, cả hai vai trò. Khớp với `platform/internal/user/Profile.java`. Không có mật khẩu.
 * Chỉ `displayName`, `phone` sửa được.
 */
export interface Profile {
  userId: Uuid;
  /** Tên đăng nhập (Nhân viên) hoặc số điện thoại, email (Chủ cửa hàng); không đổi được. */
  loginKey: string;
  displayName: string;
  role: Role;
  /**
   * Số liên hệ; `null` khi không có. Số ghi từ TK-04 trở đi có dạng `0…`; số lưu trước đó (Nhân viên tạo ở TK-02 cũ)
   * có thể còn nguyên như lúc nhập, ví dụ `028 1234 5678`, và gửi lại y nguyên thì được giữ.
   */
  phone: string | null;
  /** `null` khi không có (Nhân viên luôn `null`); không đổi được. */
  email: string | null;
}

/**
 * Body của `PUT /api/v1/platform/me` (phản hồi là `Profile`, không phải `Me`): thay cả hai trường (`phone` rỗng hoặc
 * `null` thì xóa số đang lưu). `displayName`: bắt buộc, tối đa 100 ký tự; `phone`: số Việt Nam `0` + 9 chữ số hoặc
 * `+84` + 9 chữ số, hoặc đúng số cũ đang lưu.
 */
export interface UpdateProfileRequest {
  displayName: string;
  phone: string | null;
}

/**
 * Body của `POST /api/v1/platform/me/password` (`204`). Mật khẩu hiện tại sai: `422
 * /errors/platform/wrong-current-password` (không phải 401, phiên vẫn còn). `newPassword`: 8–72 ký tự, tối đa 72 byte,
 * khác mật khẩu hiện tại.
 */
export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}
