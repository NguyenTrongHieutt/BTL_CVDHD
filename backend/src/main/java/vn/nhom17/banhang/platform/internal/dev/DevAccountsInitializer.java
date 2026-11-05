package vn.nhom17.banhang.platform.internal.dev;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Đăng nhập tạm cho GĐ0–GĐ1 (CB-04): chỉ ở profile {@code dev}, tạo 2 Cửa hàng thử với 4 tài khoản
 * {@code dev.chu.a}, {@code dev.nv.a}, {@code dev.chu.b}, {@code dev.nv.b}. Dữ liệu mẫu thật nạp bằng job seed (AD-18).
 */
@Component
@Profile("dev")
class DevAccountsInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAccountsInitializer.class);
    static final UUID STORE_A = UUID.fromString("d0000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("d0000000-0000-4000-8000-00000000000b");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;
    private final String password;

    DevAccountsInitializer(JdbcTemplate jdbc, PlatformTransactionManager txManager, PasswordEncoder passwords,
                           @Value("${app.dev.password}") String password) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.passwords = passwords;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        createStore(STORE_A, "Cửa hàng thử A (dev)", "a", "970436", "0011001234567", "CUA HANG THU A");
        createStore(STORE_B, "Cửa hàng thử B (dev)", "b", null, null, null);
        log.info("Tài khoản dev sẵn sàng: dev.chu.a, dev.nv.a, dev.chu.b, dev.nv.b (mật khẩu app.dev.password)");
    }

    private void createStore(UUID storeId, String name, String suffix, String bin, String account, String accountName) {
        TenantScope.runAs(storeId, () -> tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into app.store (id, name, phone, address, bank_bin, bank_account_no, bank_account_name)
                    values (?, ?, '0900000000', 'Địa chỉ thử', ?, ?, ?) on conflict (id) do nothing
                    """, storeId, name, bin, account, accountName);
            jdbc.update("insert into app.order_counter (store_id) values (?) on conflict (store_id) do nothing", storeId);
            user(storeId, "dev.chu." + suffix, "Chủ cửa hàng " + suffix.toUpperCase(), "OWNER");
            user(storeId, "dev.nv." + suffix, "Nhân viên " + suffix.toUpperCase(), "STAFF");
        }));
    }

    private void user(UUID storeId, String login, String displayName, String role) {
        jdbc.update("""
                insert into app.app_user (store_id, login_key, display_name, password_hash, role)
                values (?, ?, ?, ?, ?) on conflict (login_key) do nothing
                """, storeId, login, displayName, passwords.encode(password), role);
    }
}
