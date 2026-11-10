package vn.nhom17.banhang.catalog.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Gom lỗi nhập liệu theo trường để trả một lần: {@code 400 /errors/common/validation} với {@code errors[]} gồm
 * {@code field}, {@code message} (AD-19), cùng dạng với lỗi Bean Validation của {@code ApiExceptionHandler}.
 */
public final class FieldErrors {

    private final List<Map<String, String>> errors = new ArrayList<>();

    public FieldErrors add(String field, String message) {
        errors.add(Map.of("field", field, "message", message));
        return this;
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /** Có lỗi thì ném {@code 400 /errors/common/validation}. */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw toProblem();
        }
    }

    public ProblemException toProblem() {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                "Vui lòng kiểm tra lại các trường được đánh dấu.", Map.of("errors", List.copyOf(errors)));
    }

    /** Lỗi của đúng một trường. */
    public static ProblemException of(String field, String message) {
        return new FieldErrors().add(field, message).toProblem();
    }
}
