package vn.nhom17.banhang.platform.internal.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thông tin cá nhân (FR-47, TK-04): xem hồ sơ, sửa tên hiển thị và số điện thoại liên hệ, đổi mật khẩu. Cả Chủ cửa
 * hàng lẫn Nhân viên (nhánh {@code /api/v1/platform/me/**} của {@code SecurityConfig} chỉ cần đăng nhập). Chỉ tác động
 * lên chính người đang đăng nhập (lấy từ token), không có id trong đường dẫn. {@code GET /api/v1/platform/me} (phiên
 * ở frontend, {@code Me}) vẫn ở {@code AuthController}.
 */
@RestController
@RequestMapping(ProfileController.PATH)
class ProfileController {

    static final String PATH = "/api/v1/platform/me";

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    /**
     * Body {@code PUT}: thay cả hai trường; {@code phone} thiếu hoặc rỗng thì xóa số đang lưu. Trường khác
     * ({@code loginKey}, {@code email}, {@code role}) bị bỏ qua.
     *
     * @param displayName bắt buộc, tối đa 100 ký tự, chuẩn hóa như các tên khác
     * @param phone       tùy chọn, số Việt Nam: {@code 0} + 9 chữ số hoặc {@code +84} + 9 chữ số (được nhóm bằng dấu
     *                    cách, dấu chấm, gạch ngang), lưu dạng {@code 0…}
     */
    record UpdateProfileRequest(String displayName, String phone) {
    }

    /**
     * @param currentPassword mật khẩu đang dùng; sai thì {@code 422 /errors/platform/wrong-current-password}
     * @param newPassword     8–72 ký tự, tối đa 72 byte UTF-8, khác mật khẩu hiện tại
     */
    record ChangePasswordRequest(String currentPassword, String newPassword) {

        /** Không để mật khẩu lọt vào log. */
        @Override
        public String toString() {
            return "ChangePasswordRequest[]";
        }
    }

    @GetMapping("/profile")
    Profile profile() {
        return profiles.get();
    }

    @PutMapping
    Profile update(@RequestBody UpdateProfileRequest request) {
        return profiles.update(request);
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        profiles.changePassword(request);
        return ResponseEntity.noContent().build();
    }
}
