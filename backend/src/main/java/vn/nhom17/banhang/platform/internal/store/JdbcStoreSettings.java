package vn.nhom17.banhang.platform.internal.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.StoreSettings;

/**
 * Đọc thẳng {@code app.store} mỗi lần gọi, không cache: sửa ở {@code PUT /api/v1/platform/store} (TK-05) thì request
 * sau thấy ngay.
 */
@Component
class JdbcStoreSettings implements StoreSettings {

    private final JdbcTemplate jdbc;
    private final CurrentContext context;

    JdbcStoreSettings(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Override
    @Transactional(readOnly = true)
    public View current() {
        return jdbc.queryForObject(StoreRows.SELECT_BY_ID, StoreRows::map, context.storeId());
    }
}
