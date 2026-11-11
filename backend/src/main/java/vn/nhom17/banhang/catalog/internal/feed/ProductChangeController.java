package vn.nhom17.banhang.catalog.internal.feed;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.catalog.internal.FieldErrors;

/**
 * Change-feed Sản phẩm cho dịch vụ AI đánh chỉ mục Tìm kiếm ngữ nghĩa (AD-12; hợp đồng 3.2 điểm 7). Chỉ header
 * {@code X-Service-Key}, khai báo ở {@code SecurityConfig} của platform; token người dùng bị từ chối, nginx không mở
 * {@code /internal}. Chỉ đọc, thấy mọi Cửa hàng (AD-5 mục 6).
 *
 * <ul>
 *   <li>{@code after}: con trỏ mờ ({@link FeedCursor}); bỏ trống là từ đầu; hỏng thì {@code 400}.</li>
 *   <li>{@code limit}: 1–500, mặc định 500; ngoài khoảng hoặc không phải số nguyên thì {@code 400}.</li>
 *   <li>{@code nextCursor}: vị trí của phần tử cuối trang; trang rỗng thì trả lại đúng {@code after} ({@code null} khi
 *       bỏ trống). Con trỏ chỉ tiến hoặc đứng yên.</li>
 * </ul>
 */
@RestController
@RequestMapping(ProductChangeController.PATH)
class ProductChangeController {

    static final String PATH = "/internal/catalog/product-changes";
    static final int MAX_LIMIT = 500;

    private final ProductChangeRepository changes;

    ProductChangeController(ProductChangeRepository changes) {
        this.changes = changes;
    }

    /** Một trang feed. */
    record ProductChangePage(List<ProductChange> items, String nextCursor) {
    }

    @GetMapping
    ProductChangePage changes(@RequestParam(required = false) String after,
                              @RequestParam(required = false) String limit) {
        FieldErrors errors = new FieldErrors();
        FeedCursor cursor = null;
        if (after != null && !after.isEmpty()) {
            try {
                cursor = FeedCursor.decode(after);
            } catch (IllegalArgumentException e) {
                errors.add("after", "Con trỏ không hợp lệ");
            }
        }
        int pageSize = MAX_LIMIT;
        if (limit != null && !limit.isEmpty()) {
            pageSize = parseLimit(limit);
            if (pageSize < 1) {
                errors.add("limit", "Phải là số nguyên từ 1 đến " + MAX_LIMIT);
            }
        }
        errors.throwIfAny();

        List<ProductChange> items = changes.after(cursor, pageSize);
        String nextCursor = items.isEmpty()
                ? (cursor == null ? null : after)
                : FeedCursor.of(items.getLast()).encode();
        return new ProductChangePage(items, nextCursor);
    }

    /** Số nguyên 1–500; sai thì {@code -1}. */
    private static int parseLimit(String text) {
        try {
            int value = Integer.parseInt(text);
            return value >= 1 && value <= MAX_LIMIT ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
