package vn.nhom17.banhang.platform.internal.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trạng thái tài khoản cho {@code AccountStatusFilter} (AD-9): đọc {@code app.app_user.status}, cache tối đa
 * {@code accountStatusCache} (60 giây) theo {@link Clock} của ứng dụng, cả kết quả "đang hoạt động" lẫn "đã khóa".
 * Khóa, mở khóa tài khoản gọi {@link #evict} sau khi commit, nên trên máy chủ này có hiệu lực ngay ở request kế tiếp;
 * cache chỉ làm chậm thay đổi không đi qua {@code StaffAccountService} (hoặc ở máy chủ khác), chậm nhất 60 giây.
 */
@Component
public class AccountStatusCache {

    private record Entry(boolean active, Instant checkedAt) {
    }

    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final Clock clock;
    private final SecurityProperties props;

    AccountStatusCache(JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock, SecurityProperties props) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
        this.props = props;
    }

    /** Tài khoản còn {@code ACTIVE}; không có (đã xóa) cũng là không hoạt động. */
    boolean isActive(UUID userId) {
        Instant now = clock.instant();
        Entry cached = cache.get(userId);
        if (cached != null && cached.checkedAt().plus(props.accountStatusCache()).isAfter(now)) {
            return cached.active();
        }
        Boolean active = readOnly.execute(status -> jdbc.query(
                "select status from app.app_user where id = ?",
                rs -> rs.next() && "ACTIVE".equals(rs.getString(1)), userId));
        boolean result = Boolean.TRUE.equals(active);
        cache.put(userId, new Entry(result, now));
        return result;
    }

    /** Bỏ trạng thái đã cache của một tài khoản. Gọi SAU khi transaction đổi trạng thái đã commit. */
    public void evict(UUID userId) {
        cache.remove(userId);
    }
}
