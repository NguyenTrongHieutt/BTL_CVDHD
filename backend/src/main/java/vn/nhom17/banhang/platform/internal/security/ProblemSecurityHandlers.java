package vn.nhom17.banhang.platform.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.internal.web.Problems;

/** 401 và 403 của lớp bảo mật cũng trả Problem Details (AD-19). */
@Component
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;
    private final JwtService jwt;

    ProblemSecurityHandlers(JsonMapper json, JwtService jwt) {
        this.json = json;
        this.jwt = jwt;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        boolean locked = ex instanceof AccountLockedException;
        ProblemDetail pd = locked
                ? Problems.of(HttpStatus.UNAUTHORIZED, "/errors/platform/account-locked", "Tài khoản đã bị khóa",
                        "Tài khoản này đã bị khóa. Liên hệ Chủ cửa hàng.")
                : Problems.of(HttpStatus.UNAUTHORIZED, "/errors/common/unauthorized", "Chưa đăng nhập",
                        "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.");
        response.addHeader(HttpHeaders.SET_COOKIE, jwt.clearCookie().toString());
        write(response, HttpStatus.UNAUTHORIZED, pd);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, Problems.of(HttpStatus.FORBIDDEN, "/errors/common/forbidden",
                "Không có quyền", "Vai trò của bạn không được làm thao tác này."));
    }

    private void write(HttpServletResponse response, HttpStatus status, ProblemDetail pd) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), pd);
    }

    /** Tài khoản đã bị khóa trong lúc còn token (AD-9). */
    static class AccountLockedException extends AuthenticationException {
        AccountLockedException() {
            super("account locked");
        }
    }
}
