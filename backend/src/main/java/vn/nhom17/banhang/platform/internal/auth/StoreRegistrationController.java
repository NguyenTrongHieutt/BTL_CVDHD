package vn.nhom17.banhang.platform.internal.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.internal.auth.AuthController.Me;
import vn.nhom17.banhang.platform.internal.security.JwtService;

/**
 * Đăng ký Cửa hàng (FR-43, TK-00): route công khai, tạo Cửa hàng cùng tài khoản Chủ cửa hàng rồi đặt cookie phiên
 * như khi đăng nhập. Có {@code Idempotency-Key} (AD-7): lần đầu {@code 201}, gửi lại cùng nội dung {@code 200} +
 * {@code Idempotent-Replay: true}.
 */
@RestController
@RequestMapping("/api/v1/platform")
class StoreRegistrationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAY_HEADER = "Idempotent-Replay";

    private final StoreRegistrationService registrations;
    private final JwtService jwt;

    StoreRegistrationController(StoreRegistrationService registrations, JwtService jwt) {
        this.registrations = registrations;
        this.jwt = jwt;
    }

    record RegisterStoreRequest(
            @NotBlank(message = "Nhập tên Cửa hàng")
            @Size(max = 200, message = "Tên Cửa hàng tối đa 200 ký tự")
            String storeName,
            @NotNull(message = "Nhập số điện thoại hoặc email")
            @Size(max = 254, message = "Số điện thoại hoặc email tối đa 254 ký tự")
            @Pattern(regexp = LoginKeys.OWNER_LOGIN_KEY,
                    message = "Nhập số điện thoại (0xxxxxxxxx hoặc +84xxxxxxxxx) hoặc email hợp lệ")
            String loginKey,
            @NotNull(message = "Nhập mật khẩu")
            @Size(min = 8, max = 72, message = "Mật khẩu từ 8 đến 72 ký tự")
            String password,
            @NotBlank(message = "Nhập tên của bạn")
            @Size(max = 100, message = "Tên tối đa 100 ký tự")
            String displayName) {

        /** Không để mật khẩu và số điện thoại, email lọt vào log. */
        @Override
        public String toString() {
            return "RegisterStoreRequest[storeName=" + storeName + ", displayName=" + displayName + "]";
        }
    }

    @PostMapping("/stores")
    ResponseEntity<Me> register(@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                                @Valid @RequestBody RegisterStoreRequest request) {
        StoreRegistrationService.Registration result = registrations.register(parseKey(idempotencyKey), request);
        ResponseEntity.BodyBuilder response = result.replayed()
                ? ResponseEntity.ok().header(IDEMPOTENT_REPLAY_HEADER, "true")
                : ResponseEntity.status(HttpStatus.CREATED);
        return response.header(HttpHeaders.SET_COOKIE, jwt.cookie(result.session().token()).toString())
                .body(result.session().me());
    }

    /** Thiếu hoặc không phải UUID đều coi là thiếu khóa ({@code 422 idempotency-key-missing}). */
    private static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            UUID key = UUID.fromString(text);
            if (key.toString().equalsIgnoreCase(text)) {   // UUID.fromString nhận cả dạng rút gọn như "1-1-1-1-1"
                return key;
            }
        } catch (IllegalArgumentException notUuid) {
            // rơi xuống dưới
        }
        throw StoreRegistrationService.missingKey();
    }
}
