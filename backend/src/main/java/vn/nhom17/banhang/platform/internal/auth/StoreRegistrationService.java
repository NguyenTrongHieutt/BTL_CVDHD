package vn.nhom17.banhang.platform.internal.auth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.Role;
import vn.nhom17.banhang.platform.TenantScope;
import vn.nhom17.banhang.platform.internal.auth.AuthService.LoginResult;
import vn.nhom17.banhang.platform.internal.auth.StoreRegistrationController.RegisterStoreRequest;

/**
 * Đăng ký Cửa hàng (FR-43). Một transaction tạo {@code store}, {@code order_counter} và tài khoản Chủ cửa hàng
 * dưới {@link TenantScope} của Cửa hàng mới, để RLS chấp nhận các INSERT (AD-5 mục 7).
 *
 * <p>Định danh Cửa hàng suy ra từ {@code Idempotency-Key}, nên lần gửi lại rơi vào cùng phạm vi khóa của
 * {@link IdempotencyService} (khóa theo {@code store_id}). {@code request_hash} không chứa mật khẩu; khi phát lại thì
 * so mật khẩu với {@code password_hash} của Chủ cửa hàng, sai thì coi như khác nội dung.
 */
@Service
class StoreRegistrationService {

    static final String DOCUMENT_TYPE = "PLATFORM_STORE_REGISTRATION";
    private static final String STORE_ID_PREFIX = "store-registration:";
    /** Giới hạn của bcrypt tính theo byte; mật khẩu dài hơn sẽ bị bộ băm từ chối. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final PasswordEncoder passwords;
    private final AuthService auth;

    StoreRegistrationService(JdbcTemplate jdbc, IdempotencyService idempotency, PasswordEncoder passwords,
                             AuthService auth) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.passwords = passwords;
        this.auth = auth;
    }

    record Registration(LoginResult session, boolean replayed) {
    }

    /** Phần nội dung đem băm vào {@code request_hash}: đã chuẩn hóa, KHÔNG có mật khẩu. */
    record HashedContent(String storeName, String loginKey, String displayName) {
    }

    /** Gọi từ controller, ngoài transaction ({@link IdempotencyService#execute}). */
    Registration register(UUID idempotencyKey, RegisterStoreRequest request) {
        String storeName = request.storeName().strip();
        String displayName = request.displayName().strip();
        String password = request.password();
        // @NotBlank và strip() bỏ qua khoảng trắng không ngắt dòng (U+00A0, U+2007, U+202F): tên chỉ gồm chúng cũng là trống
        List<Map<String, String>> errors = new ArrayList<>();
        if (isBlank(storeName)) {
            errors.add(fieldError("storeName", "Nhập tên Cửa hàng"));
        }
        if (isBlank(displayName)) {
            errors.add(fieldError("displayName", "Nhập tên của bạn"));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            errors.add(fieldError("password", "Mật khẩu quá dài, hãy dùng mật khẩu ngắn hơn"));
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                    "Vui lòng kiểm tra lại các trường được đánh dấu.", Map.of("errors", errors));
        }
        if (idempotencyKey == null) {
            throw missingKey();
        }
        String loginKey = LoginKeys.normalize(request.loginKey());
        UUID storeId = storeIdFor(idempotencyKey);
        String requestHash = idempotency.hash(new HashedContent(storeName, loginKey, displayName));

        AtomicBoolean created = new AtomicBoolean(false);
        IdempotentResult<UUID> result = TenantScope.runAs(storeId, () -> idempotency.execute(DOCUMENT_TYPE,
                idempotencyKey, requestHash,
                () -> {
                    UUID ownerId = create(storeId, storeName, loginKey, displayName, password);
                    created.set(true);
                    return ownerId;
                },
                ownerId -> {
                    if (!created.get()) {
                        checkReplay(ownerId, password);
                    }
                    return ownerId;
                }));
        return new Registration(auth.startSession(storeId, result.value(), Role.OWNER), result.replayed());
    }

    /** Định danh Cửa hàng mới: UUID (v3) của {@code "store-registration:" + key}. Cùng khóa thì cùng Cửa hàng. */
    static UUID storeIdFor(UUID idempotencyKey) {
        return UUID.nameUUIDFromBytes((STORE_ID_PREFIX + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    private UUID create(UUID storeId, String storeName, String loginKey, String displayName, String password) {
        jdbc.update("insert into app.store (id, name) values (?, ?)", storeId, storeName);
        jdbc.update("insert into app.order_counter (store_id) values (?)", storeId);
        UUID ownerId = UUID.randomUUID();
        boolean phone = LoginKeys.isPhone(loginKey);
        try {
            jdbc.update("""
                    insert into app.app_user (id, store_id, login_key, display_name, password_hash, role, phone, email)
                    values (?, ?, ?, ?, ?, 'OWNER', ?, ?)
                    """, ownerId, storeId, loginKey, displayName, passwords.encode(password),
                    phone ? loginKey : null, phone ? null : loginKey);
        } catch (DuplicateKeyException taken) {
            // AD-9: unique toàn hệ thống trên login_key, không SELECT kiểm tra trước. Cả transaction rollback.
            throw ProblemException.conflict("/errors/platform/login-taken", "Tên đăng nhập đã được dùng",
                    "Số điện thoại hoặc email này đã được dùng. Hãy đăng nhập, hoặc đăng ký bằng số điện thoại, email khác.");
        }
        return ownerId;
    }

    private record Owner(String passwordHash, String status) {
    }

    /**
     * Phát lại cấp phiên như đăng nhập nên kiểm như đăng nhập: sai mật khẩu thì coi như khác nội dung (409);
     * tài khoản không còn ACTIVE thì cùng lỗi chung 401 của đăng nhập (FR-44).
     */
    private void checkReplay(UUID ownerId, String password) {
        List<Owner> owners = jdbc.query("select password_hash, status from app.app_user where id = ?",
                (rs, i) -> new Owner(rs.getString(1), rs.getString(2)), ownerId);
        if (owners.isEmpty() || !passwords.matches(password, owners.getFirst().passwordHash())) {
            throw ProblemException.conflict("/errors/common/idempotency-key-reuse", "Idempotency-Key đã dùng",
                    "Khóa này đã dùng cho một yêu cầu có nội dung khác. Hãy tạo khóa mới.");
        }
        if (!"ACTIVE".equals(owners.getFirst().status())) {
            throw AuthService.invalidCredentials();
        }
    }

    static ProblemException missingKey() {
        return ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                "Yêu cầu phải có header Idempotency-Key là một UUID hợp lệ.");
    }

    private static boolean isBlank(String text) {
        return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message);
    }
}
