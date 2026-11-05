package vn.nhom17.banhang.platform.internal.store;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.StoreSettings;

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
        return jdbc.queryForObject("""
                select id, name, phone, address, invoice_footer, bank_bin, bank_account_no, bank_account_name,
                       loyalty_earn_amount, loyalty_point_value, expiry_warning_days
                from app.store where id = ?
                """, (rs, i) -> new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getLong(10),
                rs.getInt(11)), context.storeId());
    }
}
