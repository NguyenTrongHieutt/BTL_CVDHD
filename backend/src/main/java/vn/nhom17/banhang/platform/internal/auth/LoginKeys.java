package vn.nhom17.banhang.platform.internal.auth;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chuẩn hóa định danh đăng nhập thành {@code login_key} (AD-9): cắt khoảng trắng hai đầu, chữ thường; số điện thoại
 * {@code +84…} lưu dạng {@code 0…}. Đăng ký, đăng nhập và tạo tài khoản Nhân viên dùng chung quy tắc này.
 */
public final class LoginKeys {

    /** Số điện thoại: {@code 0} + 9 chữ số, hoặc {@code +84} + 9 chữ số. */
    static final String PHONE = "(?:0|\\+84)[0-9]{9}";

    /** Email chỉ gồm ký tự ASCII, để {@code toLowerCase} của Java và {@code lower()} của PostgreSQL cho cùng kết quả. */
    static final String EMAIL = "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}";

    /** {@code loginKey} của Chủ cửa hàng khi đăng ký (FR-43): số điện thoại hoặc email, cho phép khoảng trắng hai đầu. */
    static final String OWNER_LOGIN_KEY = "\\s*(?:" + PHONE + "|" + EMAIL + ")\\s*";

    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile("\\+84([0-9]{9})");
    private static final Pattern LOCAL_PHONE = Pattern.compile("0[0-9]{9}");
    private static final Pattern OWNER_SHAPED = Pattern.compile(OWNER_LOGIN_KEY);

    private LoginKeys() {
    }

    public static String normalize(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT);
        Matcher international = INTERNATIONAL_PHONE.matcher(key);
        return international.matches() ? "0" + international.group(1) : key;
    }

    /** {@code loginKey} đã chuẩn hóa là số điện thoại (ngược lại là email hoặc tên đăng nhập). */
    static boolean isPhone(String normalized) {
        return LOCAL_PHONE.matcher(normalized).matches();
    }

    /**
     * Có dạng định danh của Chủ cửa hàng (số điện thoại hoặc email, kể cả khoảng trắng hai đầu). Tên đăng nhập của
     * Nhân viên không được có dạng này (FR-45), để không chiếm trước số điện thoại, email mà ai đó sẽ đăng ký.
     */
    public static boolean isOwnerShaped(String raw) {
        return OWNER_SHAPED.matcher(raw).matches();
    }
}
