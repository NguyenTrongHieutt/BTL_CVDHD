package vn.nhom17.banhang.platform;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ trả về dạng RFC 9457 Problem Details (AD-19).
 *
 * <p>{@code type} có dạng {@code /errors/<module>/<mã>} và phải có trong {@code docs/errors.md}.
 */
public class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;
    private final Map<String, Object> properties;

    public ProblemException(HttpStatus status, String type, String title, String detail) {
        this(status, type, title, detail, Map.of());
    }

    public ProblemException(HttpStatus status, String type, String title, String detail, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.properties = Map.copyOf(properties);
    }

    public static ProblemException conflict(String type, String title, String detail) {
        return new ProblemException(HttpStatus.CONFLICT, type, title, detail);
    }

    public static ProblemException unprocessable(String type, String title, String detail) {
        return new ProblemException(HttpStatus.UNPROCESSABLE_CONTENT, type, title, detail);
    }

    public static ProblemException notFound(String type, String title, String detail) {
        return new ProblemException(HttpStatus.NOT_FOUND, type, title, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
