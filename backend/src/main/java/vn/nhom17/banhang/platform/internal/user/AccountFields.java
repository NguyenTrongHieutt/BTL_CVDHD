package vn.nhom17.banhang.platform.internal.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.internal.store.StoreText;

/**
 * Kiểm trường của tài khoản, một chỗ cho cả tài khoản Nhân viên (FR-45, TK-02) lẫn Thông tin cá nhân (FR-47, TK-04):
 * tên hiển thị, mật khẩu, số điện thoại Việt Nam. Lỗi gom vào {@code errors} (mọi trường trong một lần), rồi
 * {@link #throwIfAny} trả {@code 400 /errors/common/validation} với {@code errors[]} theo trường.
 */
final class AccountFields {

    static final int DISPLAY_NAME_MAX = 100;
    static final int PASSWORD_MIN = 8;
    static final int PASSWORD_MAX = 72;
    /** Giới hạn của bcrypt tính theo byte; mật khẩu dài hơn sẽ bị bộ băm từ chối. */
    static final int PASSWORD_MAX_BYTES = 72;

    /** Câu lỗi khi số điện thoại không đúng dạng; như Khách hàng (KH-01, {@code catalog CustomerPhones}). */
    static final String PHONE_INVALID = "Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số";

    private static final Pattern VIETNAM_PHONE = Pattern.compile("(?:0|\\+84)([0-9]{9})");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[ .\\-]");

    private AccountFields() {
    }

    /**
     * Tên hiển thị đã chuẩn hóa như các tên khác ({@link StoreText}), bắt buộc, tối đa {@value #DISPLAY_NAME_MAX} ký
     * tự; hoặc ghi lỗi trên trường {@code displayName} rồi trả {@code null}. {@code label} là tên ô trong câu lỗi
     * ("Tên Nhân viên", "Tên hiển thị").
     */
    static String displayName(String raw, String label, List<Map<String, String>> errors) {
        String name = StoreText.normalize(raw);
        if (StoreText.hasInvalidChars(name)) {
            addError(errors, "displayName", label + " có ký tự không hợp lệ");
        } else if (name.isEmpty()) {
            addError(errors, "displayName", "Nhập " + Character.toLowerCase(label.charAt(0)) + label.substring(1));
        } else if (StoreText.length(name) > DISPLAY_NAME_MAX) {
            addError(errors, "displayName", label + " tối đa " + DISPLAY_NAME_MAX + " ký tự");
        } else {
            return name;
        }
        return null;
    }

    /**
     * Mật khẩu mới (lưu nguyên, không cắt khoảng trắng), hoặc ghi lỗi trên {@code field} rồi trả {@code null}.
     * {@value #PASSWORD_MIN}–{@value #PASSWORD_MAX} ký tự (code point) và tối đa {@value #PASSWORD_MAX_BYTES} byte
     * UTF-8. Trống sau khi cắt như {@code String.trim()} là thiếu: {@code @NotBlank} của đăng nhập chặn mật khẩu như
     * vậy, tài khoản sẽ không bao giờ đăng nhập được. Surrogate lẻ bị từ chối vì UTF-8 (bcrypt) đổi nó thành {@code ?}.
     * {@code label} là tên ô trong câu lỗi ("Mật khẩu", "Mật khẩu mới").
     */
    static String password(String password, String field, String label, List<Map<String, String>> errors) {
        if (password == null || password.trim().isEmpty()) {
            addError(errors, field, "Nhập " + Character.toLowerCase(label.charAt(0)) + label.substring(1));
            return null;
        }
        if (password.codePoints().anyMatch(c -> Character.getType(c) == Character.SURROGATE)) {
            addError(errors, field, label + " có ký tự không hợp lệ");
            return null;
        }
        int length = password.codePointCount(0, password.length());
        if (length < PASSWORD_MIN || length > PASSWORD_MAX) {
            addError(errors, field, label + " từ " + PASSWORD_MIN + " đến " + PASSWORD_MAX + " ký tự");
            return null;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            addError(errors, field, label + " quá dài, hãy dùng mật khẩu ngắn hơn");
            return null;
        }
        return password;
    }

    /** Mật khẩu này không thể là mật khẩu đã lưu: mọi mật khẩu được đặt đều qua {@link #password} (≤ 72 byte). */
    static boolean longerThanAnyStoredPassword(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES;
    }

    /**
     * Số điện thoại Việt Nam như của Khách hàng (KH-01): chuẩn hóa như trường văn bản ({@link StoreText}), bỏ dấu
     * cách, dấu chấm, gạch ngang dùng để nhóm số, rồi phải là {@code 0} + 9 chữ số hoặc {@code +84} + 9 chữ số; lưu
     * dạng {@code 0…} ({@code "+84 912-345-678"} → {@code "0912345678"}). Trống (kể cả {@code null}) là {@code null}
     * (không có số); sai dạng thì ghi lỗi trên trường {@code phone} và trả {@code null}.
     */
    static String vietnamPhone(String raw, List<Map<String, String>> errors) {
        String text = StoreText.normalize(raw);
        if (text.isEmpty()) {
            return null;
        }
        Matcher phone = VIETNAM_PHONE.matcher(PHONE_SEPARATORS.matcher(text).replaceAll(""));
        if (!phone.matches()) {
            addError(errors, "phone", PHONE_INVALID);
            return null;
        }
        return "0" + phone.group(1);
    }

    static void throwIfAny(List<Map<String, String>> errors) {
        if (!errors.isEmpty()) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                    "Vui lòng kiểm tra lại các trường được đánh dấu.", Map.of("errors", List.copyOf(errors)));
        }
    }

    static void addError(List<Map<String, String>> errors, String field, String message) {
        errors.add(Map.of("field", field, "message", message));
    }
}
