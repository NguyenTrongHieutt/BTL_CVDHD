package vn.nhom17.banhang.platform.internal.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.nhom17.banhang.platform.ProblemException;

/** Mọi lỗi của API trả RFC 9457 Problem Details kèm {@code requestId} (AD-19). Danh sách type: docs/errors.md. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> handleProblem(ProblemException ex) {
        ProblemDetail pd = Problems.of(ex.status(), ex.type(), ex.title(), ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        return ResponseEntity.status(ex.status()).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problems.of(HttpStatus.FORBIDDEN,
                "/errors/common/forbidden", "Không có quyền", "Vai trò của bạn không được làm thao tác này."));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Problems.of(HttpStatus.UNAUTHORIZED,
                "/errors/common/unauthorized", "Chưa đăng nhập", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Lỗi không mong đợi", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR,
                "/errors/common/internal", "Lỗi hệ thống", "Đã có lỗi xảy ra. Vui lòng thử lại; nếu vẫn lỗi, báo mã requestId."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                "Vui lòng kiểm tra lại các trường được đánh dấu.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(pd);
    }

    /**
     * Body multipart lố giới hạn của máy chủ ({@code spring.servlet.multipart.*}): bị chặn trước khi tới controller,
     * nên trả mã chung. Giới hạn nghiệp vụ của từng endpoint (ví dụ ảnh Sản phẩm 1 MB) do module đó kiểm và báo mã riêng.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).headers(headers).body(Problems.of(
                HttpStatus.CONTENT_TOO_LARGE, "/errors/common/payload-too-large", "Dữ liệu gửi lên quá lớn",
                "Tệp gửi lên vượt giới hạn của máy chủ. Hãy chọn tệp nhỏ hơn."));
    }

    /**
     * Body multipart hỏng, bị cắt hoặc bị hủy giữa chừng: lỗi của dữ liệu gửi lên, không phải lỗi máy chủ. Báo trên
     * trường {@code file} (phần tệp của endpoint nhận tệp duy nhất, ảnh Sản phẩm); chỉ ghi log WARN.
     * {@link MaxUploadSizeExceededException} (lớp con) vẫn do handler riêng ở trên xử lý.
     */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> handleBrokenMultipart(MultipartException ex) {
        log.warn("Không đọc được body multipart: {}", ex.getMostSpecificCause().toString());
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                "Vui lòng kiểm tra lại các trường được đánh dấu.");
        pd.setProperty("errors", List.of(Map.of("field", "file",
                "message", "Không đọc được tệp gửi lên. Hãy chọn lại tệp rồi thử lại.")));
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            Problems.withRequestId(pd);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
