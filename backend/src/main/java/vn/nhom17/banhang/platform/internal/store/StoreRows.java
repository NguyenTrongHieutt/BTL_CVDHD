package vn.nhom17.banhang.platform.internal.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import vn.nhom17.banhang.platform.StoreSettings.View;

/** Câu đọc một dòng {@code app.store} dùng chung cho {@link JdbcStoreSettings} và {@link StoreService}. */
final class StoreRows {

    /** Tham số: id Cửa hàng. RLS chỉ cho thấy dòng của Cửa hàng hiện tại (AD-5). */
    static final String SELECT_BY_ID = """
            select id, name, phone, address, invoice_footer, bank_bin, bank_account_no, bank_account_name,
                   loyalty_earn_amount, loyalty_point_value, expiry_warning_days
            from app.store where id = ?
            """;

    private StoreRows() {
    }

    static View map(ResultSet rs, int rowNum) throws SQLException {
        return new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getLong(10),
                rs.getInt(11));
    }
}
