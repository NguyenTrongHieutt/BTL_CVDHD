package vn.nhom17.banhang.platform.internal.user;

import java.util.regex.Pattern;
import vn.nhom17.banhang.platform.internal.store.StoreText;

/** Tìm người thực hiện trong platform; không phụ thuộc helper nội bộ của catalog (AD-2). */
final class StaffAccountSearch {

    private static final Pattern SPACE_RUNS = Pattern.compile(" {2,}");
    private static final String MATCH_FOLD = "regexp_replace(lower(app.f_unaccent(%s)), '\\s+', ' ', 'g')";

    private StaffAccountSearch() {
    }

    /** Bỏ ký tự vô hình, chuẩn NFC, gộp khoảng trắng; trống thì bỏ qua bộ lọc. */
    static String query(String raw) {
        StringBuilder kept = new StringBuilder();
        StoreText.normalize(raw).codePoints()
                .map(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) ? ' ' : c)
                .filter(c -> !Character.isISOControl(c))
                .forEach(kept::appendCodePoint);
        String text = SPACE_RUNS.matcher(kept).replaceAll(" ").strip();
        return text.isEmpty() ? null : text;
    }

    /** Bỏ dấu cả hai phía, rồi thoát LIKE để % _ \\ luôn được tìm như ký tự thường. */
    static String containsFolded(String column) {
        return MATCH_FOLD.formatted(column)
                + " like ('%' || replace(replace(replace(" + MATCH_FOLD.formatted("?")
                + ", '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%') escape '\\'";
    }
}
