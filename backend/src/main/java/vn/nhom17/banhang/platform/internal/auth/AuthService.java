package vn.nhom17.banhang.platform.internal.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.Role;
import vn.nhom17.banhang.platform.TenantScope;
import vn.nhom17.banhang.platform.internal.auth.AuthController.Me;
import vn.nhom17.banhang.platform.internal.security.JwtService;
import vn.nhom17.banhang.platform.internal.user.AppUserRepository;

@Service
class AuthService {

    /** Băm giả để so khi không có tài khoản, tránh lộ tài khoản qua thời gian phản hồi (FR-44). */
    private static final String DUMMY_HASH =
            "{bcrypt}$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z6f5bJ3rS2YQm4m0f5oGkq3K";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final CurrentContext context;
    private final AppUserRepository users;

    AuthService(JdbcTemplate jdbc, PlatformTransactionManager txManager, PasswordEncoder passwords, JwtService jwt,
                CurrentContext context, AppUserRepository users) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
        this.passwords = passwords;
        this.jwt = jwt;
        this.context = context;
        this.users = users;
    }

    record LoginResult(String token, Me me) {
    }

    private record Lookup(UUID userId, UUID storeId, String passwordHash, Role role, String status, String displayName) {
    }

    LoginResult login(String loginKey, String password) {
        // auth_lookup là SECURITY DEFINER: ngoại lệ đọc vượt Cửa hàng duy nhất của backend (AD-5 mục 6)
        List<Lookup> found = readOnly.execute(s -> jdbc.query(
                "select user_id, store_id, password_hash, role, status, display_name from app.auth_lookup(?)",
                (rs, i) -> new Lookup(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        Role.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6)),
                loginKey.trim().toLowerCase()));
        Lookup user = found == null || found.isEmpty() ? null : found.getFirst();
        boolean ok = passwords.matches(password, user == null ? DUMMY_HASH : user.passwordHash());
        if (user == null || !ok || !"ACTIVE".equals(user.status())) {
            throw new ProblemException(HttpStatus.UNAUTHORIZED, "/errors/platform/invalid-credentials",
                    "Đăng nhập không thành công", "Sai tên đăng nhập hoặc mật khẩu.");
        }
        String token = jwt.issue(user.userId(), user.storeId(), user.role());
        Me me = TenantScope.runAs(user.storeId(), user.userId(), user.role(), this::loadMe);
        return new LoginResult(token, me);
    }

    Me me() {
        return loadMe();
    }

    private Me loadMe() {
        return readOnly.execute(s -> {
            UUID storeId = context.storeId();
            UUID userId = context.userId();
            String storeName = jdbc.queryForObject("select name from app.store where id = ?", String.class, storeId);
            // Đọc qua entity có @TenantId để dùng cả lớp lọc của Hibernate (AD-5 mục 2)
            String displayName = users.findById(userId).map(u -> u.displayName()).orElseThrow(() ->
                    new ProblemException(HttpStatus.UNAUTHORIZED, "/errors/common/unauthorized", "Chưa đăng nhập",
                            "Tài khoản không còn tồn tại."));
            return new Me(userId, storeId, storeName, context.role(), displayName);
        });
    }
}
