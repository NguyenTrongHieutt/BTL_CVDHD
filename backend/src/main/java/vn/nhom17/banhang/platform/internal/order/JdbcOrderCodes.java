package vn.nhom17.banhang.platform.internal.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.OrderCodes;

@Component
class JdbcOrderCodes implements OrderCodes {

    private final JdbcTemplate jdbc;
    private final CurrentContext context;

    JdbcOrderCodes(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextOrderCode() {
        Long value = jdbc.queryForObject("""
                update app.order_counter set next_value = next_value + 1
                where store_id = ? returning next_value - 1
                """, Long.class, context.storeId());
        if (value == null) {
            throw new IllegalStateException("Cửa hàng chưa có order_counter (tạo khi đăng ký Cửa hàng)");
        }
        return "DH-%06d".formatted(value);
    }
}
