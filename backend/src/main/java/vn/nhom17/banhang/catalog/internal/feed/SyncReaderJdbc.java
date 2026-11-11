package vn.nhom17.banhang.catalog.internal.feed;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kết nối thứ hai của backend, chạy bằng role {@code sync_reader} ({@code BYPASSRLS}, chỉ SELECT), cho change-feed đọc
 * Sản phẩm của mọi Cửa hàng (một trong hai ngoại lệ đọc vượt Cửa hàng, AD-5 mục 6; AD-12).
 *
 * <ul>
 *   <li>Pool Hikari nằm trong bean này, KHÔNG là bean {@code DataSource}: {@code DataSource}, {@code JdbcTemplate},
 *       JPA, Flyway, {@code TenantTransactionManager} của Spring Boot vẫn chỉ thấy datasource chính
 *       ({@code app_backend}, không {@code BYPASSRLS}), và health {@code db} của actuator không kiểm pool này (sai mật
 *       khẩu sync không làm {@code /actuator/health} thành DOWN). Đóng pool khi ứng dụng dừng ({@link #close()}).</li>
 *   <li>Chỉ đọc hai lớp: {@code readOnly} của Hikari cộng {@code readOnlyMode=always} của PgJDBC (mặc định PgJDBC bỏ
 *       qua read-only khi autocommit), ngoài ra role chỉ có quyền SELECT.</li>
 *   <li>Tối đa 2 kết nối, không giữ kết nối rỗi, mở ở lần dùng đầu. Chờ kết nối tối đa 5 giây, mỗi câu lệnh tối đa 8
 *       giây.</li>
 *   <li>Không có transaction manager: mỗi lần đọc là một câu lệnh autocommit. Chỉ {@link ProductChangeRepository}
 *       dùng bean này.</li>
 * </ul>
 */
public final class SyncReaderJdbc implements AutoCloseable {

    static final int MAX_POOL_SIZE = 2;
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(8);

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    SyncReaderJdbc(SyncReaderProperties props) {
        pool = new HikariDataSource();
        pool.setPoolName("sync-reader");
        pool.setJdbcUrl(props.url());
        pool.setUsername(props.username());
        pool.setPassword(props.password());
        pool.setReadOnly(true);
        pool.addDataSourceProperty("readOnlyMode", "always");
        pool.setMaximumPoolSize(MAX_POOL_SIZE);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout((int) QUERY_TIMEOUT.toSeconds());
    }

    /** {@code JdbcTemplate} chỉ đọc trên pool {@code sync_reader}. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        pool.close();
    }
}
