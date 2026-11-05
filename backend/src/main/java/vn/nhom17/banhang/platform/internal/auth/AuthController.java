package vn.nhom17.banhang.platform.internal.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.Role;
import vn.nhom17.banhang.platform.internal.security.JwtService;

/**
 * Phiên ở frontend (AD-9). Cookie {@code HttpOnly} nên JavaScript không đọc được token; frontend gọi
 * {@code GET /api/v1/platform/me} khi mở ứng dụng.
 */
@RestController
@RequestMapping("/api/v1/platform")
class AuthController {

    private final AuthService auth;
    private final JwtService jwt;

    AuthController(AuthService auth, JwtService jwt) {
        this.auth = auth;
        this.jwt = jwt;
    }

    record LoginRequest(@NotBlank String loginKey, @NotBlank String password) {
    }

    record Me(UUID userId, UUID storeId, String storeName, Role role, String displayName) {
    }

    @PostMapping("/auth/login")
    ResponseEntity<Me> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.login(request.loginKey(), request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwt.cookie(result.token()).toString())
                .body(result.me());
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, jwt.clearCookie().toString()).build();
    }

    @GetMapping("/me")
    Me me() {
        return auth.me();
    }
}
