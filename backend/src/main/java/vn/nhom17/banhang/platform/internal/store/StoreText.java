package vn.nhom17.banhang.platform.internal.store;

import java.text.Normalizer;

/**
 * Chuẩn hóa trường văn bản một dòng của Thông tin Cửa hàng, cùng quy tắc với trường một dòng của catalog
 * ({@code catalog.internal.TextFields}; platform không được import catalog, AD-2):
 *
 * <ol>
 *   <li>Bỏ ký tự định dạng vô hình (loại Cf như U+200B, U+FEFF, U+2060).</li>
 *   <li>Đổi khoảng trắng Unicode loại Zs (U+00A0, U+3000…) thành dấu cách thường.</li>
 *   <li>Chuẩn NFC, rồi cắt khoảng trắng hai đầu.</li>
 * </ol>
 *
 * {@link #hasInvalidChars} chặn ký tự điều khiển (NUL, tab, xuống dòng…), surrogate lẻ, U+2028, U+2029. Độ dài đếm
 * theo code point sau khi chuẩn hóa ({@link #length}). Đăng ký Cửa hàng cũng dùng các quy tắc này cho tên Cửa hàng và
 * tên Chủ cửa hàng, để tên lưu lúc đăng ký luôn lưu lại được ở Thông tin Cửa hàng.
 */
public final class StoreText {

    private StoreText() {
    }

    /** {@code null} thành chuỗi rỗng. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().forEach(c -> {
            int type = Character.getType(c);
            if (type == Character.FORMAT) {
                return;
            }
            kept.appendCodePoint(type == Character.SPACE_SEPARATOR ? ' ' : c);
        });
        return strip(Normalizer.normalize(kept, Normalizer.Form.NFC));
    }

    public static boolean hasInvalidChars(String text) {
        return text.codePoints().anyMatch(c -> {
            int type = Character.getType(c);
            return type == Character.SURROGATE || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || Character.isISOControl(c);
        });
    }

    /** Số ký tự theo code point (emoji là một ký tự). */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isSpace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
