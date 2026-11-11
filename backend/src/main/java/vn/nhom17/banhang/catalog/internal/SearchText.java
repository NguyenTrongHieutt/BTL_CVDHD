package vn.nhom17.banhang.catalog.internal;

import java.util.regex.Pattern;

/**
 * Tìm kiếm thường theo tên trong catalog (FR-5 SP-05, FR-19 KH-02): một nơi chuẩn hóa {@code q} và dựng điều kiện
 * "tên chứa {@code q}, không phân biệt hoa thường, không cần dấu", để Sản phẩm và Khách hàng khớp cùng một cách.
 *
 * <ul>
 *   <li>{@link #query}: chuẩn hóa như trường văn bản ({@link TextFields#normalize}), tab, xuống dòng thành dấu cách,
 *       bỏ ký tự điều khiển khác, gộp khoảng trắng liền nhau; rỗng thì {@code null} (bỏ qua).</li>
 *   <li>{@link #containsFolded}: {@code lower(app.f_unaccent(…))} cả hai phía, gộp khoảng trắng; {@code %}
 *       {@code _} {@code \} trong {@code q} được thoát SAU khi bỏ dấu, nên chỉ khớp đúng chữ đó.</li>
 * </ul>
 */
public final class SearchText {

    /** Tên bỏ dấu rồi mới chữ thường ({@code unaccent} có thể sinh chữ hoa, ví dụ "Æ" → "AE"); dùng để sắp. */
    private static final String FOLD = "lower(app.f_unaccent(%s))";

    /** Chữ đem so khớp: bỏ dấu, chữ thường, gộp khoảng trắng liền nhau (cả chuỗi {@code unaccent} sinh ra). */
    private static final String MATCH_FOLD = "regexp_replace(" + FOLD + ", '\\s+', ' ', 'g')";

    private static final Pattern SPACE_RUNS = Pattern.compile(" {2,}");

    private SearchText() {
    }

    /**
     * {@code q} sau khi chuẩn hóa như trường văn bản (bỏ ký tự định dạng vô hình, khoảng trắng Unicode thành dấu cách,
     * NFC, cắt hai đầu). Tab, xuống dòng (chữ dán từ nơi khác: "Sữa\ttươi") thành dấu cách; ký tự điều khiển khác bị
     * bỏ (chữ đã lưu không có, PostgreSQL không nhận NUL); khoảng trắng liền nhau gộp làm một. Rỗng thì {@code null}.
     */
    public static String query(String raw) {
        StringBuilder kept = new StringBuilder();
        TextFields.normalize(raw, false).codePoints()
                .map(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) ? ' ' : c)
                .filter(c -> !Character.isISOControl(c))
                .forEach(kept::appendCodePoint);
        String text = SPACE_RUNS.matcher(kept).replaceAll(" ").strip();
        return text.isEmpty() ? null : text;
    }

    /** {@code lower(app.f_unaccent(column))}: khóa sắp theo tên không dấu. */
    public static String folded(String column) {
        return FOLD.formatted(column);
    }

    /**
     * Điều kiện SQL "{@code column} chứa {@code q}" không phân biệt hoa thường, không cần dấu, với MỘT tham số là
     * {@code q} đã qua {@link #query}. Mẫu dựng trong SQL: bỏ dấu, chữ thường, gộp khoảng trắng, RỒI mới thoát
     * {@code \ % _}, để ký tự mà {@code unaccent} sinh ra cũng được thoát. {@code column} {@code null} thì không khớp.
     */
    public static String containsFolded(String column) {
        return MATCH_FOLD.formatted(column)
                + " like ('%' || replace(replace(replace(" + MATCH_FOLD.formatted("?")
                + ", '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%') escape '\\'";
    }

    /** Thoát ký tự đặc biệt của {@code LIKE} (thoát bằng {@code \}). */
    public static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
