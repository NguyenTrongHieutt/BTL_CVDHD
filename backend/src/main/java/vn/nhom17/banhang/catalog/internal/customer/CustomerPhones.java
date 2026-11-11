package vn.nhom17.banhang.catalog.internal.customer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.nhom17.banhang.catalog.internal.TextFields;

/**
 * Số điện thoại Khách hàng (FR-18, FR-19). Cùng quy tắc với {@code login_key} của platform (AD-9) nhưng viết riêng ở
 * catalog, vì catalog không import {@code platform.internal}:
 *
 * <ul>
 *   <li>Số Việt Nam: {@code 0} + 9 chữ số, hoặc {@code +84} + 9 chữ số; lưu dạng {@code 0…} (10 chữ số).</li>
 *   <li>Khi nhập được dùng dấu cách, dấu chấm, gạch ngang để nhóm số ("0912 345 678", "0912.345.678",
 *       "+84 912-345-678"); bỏ hết khi lưu. Chuỗi được chuẩn hóa như trường văn bản trước ({@link TextFields#normalize}:
 *       khoảng trắng Unicode thành dấu cách, bỏ ký tự định dạng vô hình).</li>
 *   <li>Tìm kiếm ({@link #searchDigits}): chỉ so chữ số, {@code +84} ở đầu hiểu như {@code 0}.</li>
 * </ul>
 */
final class CustomerPhones {

    /** Câu lỗi khi số không đúng dạng. */
    static final String INVALID = "Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số";

    private static final Pattern PHONE = Pattern.compile("(?:0|\\+84)([0-9]{9})");
    private static final Pattern SEPARATORS = Pattern.compile("[ .\\-]");
    private static final Pattern NOT_DIGIT = Pattern.compile("[^0-9]");
    private static final Pattern NOT_DIGIT_OR_PLUS = Pattern.compile("[^0-9+]");

    private CustomerPhones() {
    }

    /** Chuỗi trống sau khi chuẩn hóa (kể cả {@code null}): thiếu số điện thoại. */
    static boolean isBlank(String raw) {
        return TextFields.normalize(raw, false).isEmpty();
    }

    /** Số đã chuẩn hóa ({@code 0} + 9 chữ số), hoặc {@code null} khi không đúng dạng (kể cả {@code null}, trống). */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = SEPARATORS.matcher(TextFields.normalize(raw, false)).replaceAll("");
        Matcher phone = PHONE.matcher(compact);
        return phone.matches() ? "0" + phone.group(1) : null;
    }

    /**
     * Phần số điện thoại của từ khóa tìm kiếm {@code query} (đã chuẩn hóa): các chữ số của nó, {@code +84} ở đầu đổi
     * thành {@code 0} ("345 678" → "345678", "+84 912" → "0912"). {@code null} khi từ khóa có chữ cái (đang tìm theo
     * tên: "Chị Hoa 2" không khớp mọi số có chữ số 2) hoặc không có chữ số nào.
     */
    static String searchDigits(String query) {
        if (query == null || query.codePoints().anyMatch(Character::isLetter)) {
            return null;
        }
        String kept = NOT_DIGIT_OR_PLUS.matcher(query).replaceAll("");
        String digits = kept.startsWith("+84")
                ? "0" + NOT_DIGIT.matcher(kept.substring(3)).replaceAll("")
                : NOT_DIGIT.matcher(kept).replaceAll("");
        return digits.isEmpty() ? null : digits;
    }
}
