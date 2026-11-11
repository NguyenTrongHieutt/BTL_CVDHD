package vn.nhom17.banhang.catalog.internal.feed;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Đọc change-feed từ view {@code app.catalog_product_changes} (AD-12) bằng kết nối {@code sync_reader}
 * ({@link SyncReaderJdbc}): thấy Sản phẩm của mọi Cửa hàng, không đặt ngữ cảnh Cửa hàng, không mở transaction (không
 * {@code @Transactional}: transaction manager của ứng dụng là của datasource chính). Chỉ đọc view này, không đọc bảng
 * nào khác.
 *
 * <p>Sắp theo {@code (changed_at, product_id)}. Chỉ trả dòng có {@code changed_at <= giờ hiện tại - }{@link #SAFETY_LAG},
 * giờ lấy từ {@code Clock} của ứng dụng, cùng nguồn giờ với {@code changed_at} (AD-8): {@code changed_at} lấy giờ lúc
 * ghi, trước khi commit, nên transaction commit muộn mà {@code changed_at} sớm hơn vẫn kịp hiện ra trước khi con trỏ
 * vượt qua nó, miễn là bên ghi commit trong vòng {@link #SAFETY_LAG}. Thay đổi mới hơn có ở lần đọc sau.
 */
@Component
public class ProductChangeRepository {

    /** Độ trễ an toàn: dòng đổi chưa quá khoảng này thì chưa trả. */
    public static final Duration SAFETY_LAG = Duration.ofSeconds(5);

    private static final String SELECT = """
            select store_id, product_id, search_text, status, changed_at
            from app.catalog_product_changes
            where changed_at <= ?
            """;
    private static final String AFTER_CURSOR = "  and (changed_at, product_id) > (?::timestamptz, ?::uuid)\n";
    private static final String ORDER_AND_LIMIT = "order by changed_at, product_id\nlimit ?";

    private static final RowMapper<ProductChange> ROW = (rs, i) -> new ProductChange(
            rs.getObject("store_id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getString("search_text"), rs.getString("status"),
            rs.getObject("changed_at", OffsetDateTime.class).toInstant());

    private final SyncReaderJdbc syncReader;
    private final Clock clock;

    ProductChangeRepository(SyncReaderJdbc syncReader, Clock clock) {
        this.syncReader = syncReader;
        this.clock = clock;
    }

    /**
     * Tối đa {@code limit} thay đổi đứng sau {@code cursor} ({@code null}: từ đầu feed).
     */
    List<ProductChange> after(FeedCursor cursor, int limit) {
        OffsetDateTime settled = OffsetDateTime.ofInstant(clock.instant().minus(SAFETY_LAG), ZoneOffset.UTC);
        if (cursor == null) {
            return syncReader.jdbc().query(SELECT + ORDER_AND_LIMIT, ROW, settled, limit);
        }
        return syncReader.jdbc().query(SELECT + AFTER_CURSOR + ORDER_AND_LIMIT, ROW, settled,
                OffsetDateTime.ofInstant(cursor.changedAt(), ZoneOffset.UTC), cursor.productId(), limit);
    }
}
