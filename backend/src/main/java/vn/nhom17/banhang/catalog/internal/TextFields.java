package vn.nhom17.banhang.catalog.internal;

import java.text.Normalizer;

/**
 * Chuẩn hóa và kiểm trường văn bản người dùng nhập trong catalog (tên Danh mục; tên, đơn vị tính, mô tả, SKU, mã vạch
 * của Sản phẩm). Một nơi cho mọi trường, để cùng một chuỗi luôn ra cùng một kết quả:
 *
 * <ol>
 *   <li>Bỏ ký tự định dạng vô hình (loại Cf như U+200B, U+FEFF, U+2060), để không lách được ràng buộc unique bằng
 *       chuỗi trông giống hệt. Trường nhiều dòng giữ U+200C, U+200D (ZWNJ, ZWJ) để emoji ghép không bị tách.</li>
 *   <li>Đổi mọi khoảng trắng Unicode loại Zs (U+00A0, U+2009, U+3000…) thành dấu cách thường, để tên, SKU trông giống
 *       hệt không thành hai giá trị khác nhau.</li>
 *   <li>Chuẩn NFC: chữ có dấu gõ dạng tổ hợp (NFD) thành một ký tự.</li>
 *   <li>Cắt khoảng trắng hai đầu.</li>
 *   <li>Từ chối ký tự điều khiển còn lại (NUL, tab, xuống dòng…), surrogate lẻ (chuỗi UTF-16 hỏng, sẽ bị lưu thành
 *       {@code ?}), và ở trường một dòng cả U+2028, U+2029 (ngắt dòng, ngắt đoạn). Trường nhiều dòng cho phép xuống
 *       dòng và tab ({@code \r\n}, {@code \r} đổi thành {@code \n}).</li>
 *   <li>Đếm độ dài theo code point sau khi chuẩn hóa.</li>
 * </ol>
 *
 * Lỗi ghi vào {@link FieldErrors} bằng câu dựng từ {@link Rule#label()}: "Nhập tên Sản phẩm", "Tên Sản phẩm tối đa
 * 200 ký tự", "Tên Sản phẩm có ký tự không hợp lệ".
 */
public final class TextFields {

    private TextFields() {
    }

    /**
     * Quy tắc của một trường. {@code label} viết hoa chữ đầu, như nhãn trên form ("Tên Danh mục", "Mã vạch").
     *
     * @param multiline cho phép xuống dòng và tab (mô tả)
     */
    public record Rule(String label, int max, boolean required, boolean multiline) {

        public static Rule required(String label, int max) {
            return new Rule(label, max, true, false);
        }

        public static Rule optional(String label, int max) {
            return new Rule(label, max, false, false);
        }

        /** Như quy tắc này nhưng cho phép xuống dòng và tab. */
        public Rule allowingLineBreaks() {
            return new Rule(label, max, required, true);
        }
    }

    /**
     * Chuẩn hóa {@code raw} theo {@code rule}. Trả chuỗi đã chuẩn hóa (chuỗi rỗng khi trường không bắt buộc và bỏ
     * trống), hoặc {@code null} sau khi ghi lỗi của {@code field} vào {@code errors}.
     */
    public static String check(String raw, String field, Rule rule, FieldErrors errors) {
        String text = normalize(raw, rule.multiline());
        if (hasControlChars(text, rule.multiline())) {
            errors.add(field, rule.label() + " có ký tự không hợp lệ");
            return null;
        }
        if (text.isEmpty() && rule.required()) {
            errors.add(field, "Nhập " + lowerFirst(rule.label()));
            return null;
        }
        if (length(text) > rule.max()) {
            errors.add(field, rule.label() + " tối đa " + rule.max() + " ký tự");
            return null;
        }
        return text;
    }

    /** Các bước 1–4 (và đổi {@code \r\n}, {@code \r} thành {@code \n} khi nhiều dòng); {@code null} thành chuỗi rỗng. */
    public static String normalize(String raw, boolean multiline) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().forEach(c -> {
            int type = Character.getType(c);
            if (type == Character.FORMAT && !(multiline && (c == ZWNJ || c == ZWJ))) {
                return;
            }
            kept.appendCodePoint(type == Character.SPACE_SEPARATOR ? ' ' : c);
        });
        String text = Normalizer.normalize(kept, Normalizer.Form.NFC);
        if (multiline) {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
        }
        return strip(text);
    }

    /** Số ký tự theo code point (chữ ngoài BMP như emoji là một ký tự). */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static final int ZWNJ = 0x200C;
    private static final int ZWJ = 0x200D;

    /** Ký tự điều khiển, surrogate lẻ, và (trường một dòng) U+2028, U+2029. */
    private static boolean hasControlChars(String text, boolean multiline) {
        return text.codePoints().anyMatch(c -> {
            int type = Character.getType(c);
            if (type == Character.SURROGATE) {
                return true;
            }
            if (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                return !multiline;
            }
            return Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t'));
        });
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

    /** "Tên Danh mục" → "tên Danh mục": chỉ chữ đầu, giữ nguyên thuật ngữ viết hoa phía sau. */
    private static String lowerFirst(String label) {
        if (label.isEmpty()) {
            return label;
        }
        int first = label.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toLowerCase(first))
                .append(label, Character.charCount(first), label.length()).toString();
    }
}
