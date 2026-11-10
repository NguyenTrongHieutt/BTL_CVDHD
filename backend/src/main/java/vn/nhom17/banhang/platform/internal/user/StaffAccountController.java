package vn.nhom17.banhang.platform.internal.user;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.PageResponse;

/**
 * Tài khoản Nhân viên (FR-45, TK-02): xem người dùng của Cửa hàng, tạo Nhân viên, khóa, mở khóa, đặt lại mật khẩu.
 * Chỉ Chủ cửa hàng (nhánh {@code /api/v1/platform/**} của {@code SecurityConfig}, AD-16). Cửa hàng lấy từ phiên đăng
 * nhập. Không có {@code Idempotency-Key}: tài khoản không phải chứng từ; gửi lại lần tạo đã bị unique của
 * {@code login_key} chặn ({@code 409 login-taken}); khóa, mở khóa gửi lại không đổi gì.
 */
@RestController
@RequestMapping(StaffAccountController.PATH)
class StaffAccountController {

    static final String PATH = "/api/v1/platform/users";

    private final StaffAccountService accounts;

    StaffAccountController(StaffAccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * Body tạo Nhân viên; kiểm ở {@link StaffAccountService}, trả lỗi theo trường (mọi trường trong một lần).
     *
     * @param loginKey    3–50 ký tự {@code a-z 0-9 . _ -}, bắt đầu bằng chữ cái hoặc chữ số, lưu chữ thường; không
     *                    được có dạng số điện thoại hay email (dành cho Chủ cửa hàng); duy nhất toàn hệ thống
     * @param displayName bắt buộc, tối đa 100 ký tự
     * @param password    8–72 ký tự và tối đa 72 byte UTF-8
     * @param phone       tùy chọn, tối đa 20 ký tự: chữ số, khoảng trắng, {@code +}, {@code .}, {@code -}
     */
    record CreateStaffRequest(String loginKey, String displayName, String password, String phone) {

        /** Không để mật khẩu lọt vào log. */
        @Override
        public String toString() {
            return "CreateStaffRequest[loginKey=" + loginKey + ", displayName=" + displayName + "]";
        }
    }

    /** @param password mật khẩu mới, 8–72 ký tự và tối đa 72 byte UTF-8; không gửi mật khẩu cũ */
    record ResetPasswordRequest(String password) {

        @Override
        public String toString() {
            return "ResetPasswordRequest[]";
        }
    }

    /** Mọi người dùng của Cửa hàng: Chủ cửa hàng trước, rồi Nhân viên mới tạo trước. */
    @GetMapping
    PageResponse<StoreUser> list(@RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size) {
        return accounts.list(page, size);
    }

    @PostMapping
    ResponseEntity<StoreUser> create(@RequestBody CreateStaffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.create(request));
    }

    @PostMapping("/{id}/lock")
    StoreUser lock(@PathVariable UUID id) {
        return accounts.lock(id);
    }

    @PostMapping("/{id}/unlock")
    StoreUser unlock(@PathVariable UUID id) {
        return accounts.unlock(id);
    }

    @PostMapping("/{id}/reset-password")
    ResponseEntity<Void> resetPassword(@PathVariable UUID id, @RequestBody ResetPasswordRequest request) {
        accounts.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }
}
