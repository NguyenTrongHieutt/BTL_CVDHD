package vn.nhom17.banhang.platform.internal.user;

import static vn.nhom17.banhang.platform.internal.user.AccountFields.addError;
import static vn.nhom17.banhang.platform.internal.user.AccountFields.throwIfAny;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.Role;
import vn.nhom17.banhang.platform.internal.auth.LoginKeys;
import vn.nhom17.banhang.platform.internal.security.AccountStatusCache;
import vn.nhom17.banhang.platform.internal.store.StoreText;
import vn.nhom17.banhang.platform.internal.user.StaffAccountController.CreateStaffRequest;
import vn.nhom17.banhang.platform.internal.user.StaffAccountController.ResetPasswordRequest;

/**
 * Quản lý tài khoản Nhân viên (FR-45, TK-02).
 *
 * <ul>
 *   <li><b>Tạo:</b> {@code loginKey} {@value #LOGIN_KEY_MIN}–{@value #LOGIN_KEY_MAX} ký tự {@code a-z 0-9 . _ -},
 *       bắt đầu bằng chữ cái hoặc chữ số, lưu chữ thường ({@link LoginKeys#normalize}, như khi đăng nhập); không được
 *       có dạng số điện thoại hay email (dành cho Chủ cửa hàng). Trùng toàn hệ thống do unique
 *       {@code app_user_login_key_uq} bắt, không SELECT kiểm trước (AD-9): {@code 409 /errors/platform/login-taken}.
 *       {@code displayName} chuẩn hóa như các tên khác ({@link StoreText}), bắt buộc, tối đa
 *       {@value AccountFields#DISPLAY_NAME_MAX}. Mật khẩu {@value AccountFields#PASSWORD_MIN}–
 *       {@value AccountFields#PASSWORD_MAX} ký tự (code point) và tối đa {@value AccountFields#PASSWORD_MAX_BYTES}
 *       byte UTF-8 (giới hạn của bcrypt); không được trống sau khi cắt khoảng trắng (đăng nhập chặn mật khẩu như vậy bằng
 *       {@code @NotBlank}) hay có surrogate lẻ (UTF-8 đổi nó thành {@code ?}). {@code phone} tùy chọn, số Việt Nam như
 *       Thông tin cá nhân (TK-04) và Khách hàng (KH-01), lưu dạng {@code 0…}. Mọi lỗi nhập liệu trả một lần
 *       ({@code 400 /errors/common/validation}). Tên, mật khẩu, số điện thoại kiểm ở {@link AccountFields}, dùng chung
 *       với Thông tin cá nhân.</li>
 *   <li><b>Khóa, mở khóa, đặt lại mật khẩu:</b> chỉ trên Nhân viên ({@code role = STAFF}) của Cửa hàng mình. Chủ cửa
 *       hàng (kể cả chính mình): {@code 409 /errors/platform/owner-protected}; id của Cửa hàng khác hoặc không có:
 *       {@code 404 /errors/common/not-found} (AD-5). Dòng bị khóa ({@code FOR UPDATE}) nên hai lần cùng lúc chạy lần
 *       lượt. Khóa tài khoản đã khóa, mở khóa tài khoản đang hoạt động: {@code 200}, không ghi gì.</li>
 *   <li><b>Nhật ký</b> trong cùng transaction: {@link AuditAction#PLATFORM_USER_CREATE},
 *       {@link AuditAction#PLATFORM_USER_LOCK}, {@link AuditAction#PLATFORM_USER_UNLOCK} ({@code before}/{@code after}
 *       là {@code status}), {@link AuditAction#PLATFORM_USER_RESET_PASSWORD} (không có {@code before}/{@code after}).
 *       Không bao giờ chứa mật khẩu.</li>
 *   <li>Tài khoản bị khóa không đăng nhập được ({@code 401 invalid-credentials}); phiên đang mở bị chặn
 *       ({@code 401 account-locked}) ngay ở request kế tiếp: khóa, mở khóa xong (đã commit) thì bỏ trạng thái đã cache
 *       của tài khoản đó ({@link AccountStatusCache#evict}).</li>
 *   <li>Kiểm dữ liệu và băm mật khẩu (bcrypt, chậm) chạy TRƯỚC khi mở transaction; transaction chỉ bọc phần ghi
 *       database, để không giữ kết nối của pool lâu.</li>
 * </ul>
 */
@Service
class StaffAccountService {

    static final int LOGIN_KEY_MIN = 3;
    static final int LOGIN_KEY_MAX = 50;
    static final String LOGIN_KEY_UNIQUE = "app_user_login_key_uq";
    static final String LOGIN_TAKEN = "/errors/platform/login-taken";
    static final String OWNER_PROTECTED = "/errors/platform/owner-protected";

    private static final Pattern LOGIN_KEY_CHARS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final RowMapper<StoreUser> ROW = (rs, i) -> new StoreUser(rs.getObject("id", UUID.class),
            rs.getString("login_key"), rs.getString("display_name"), Role.valueOf(rs.getString("role")),
            UserStatus.valueOf(rs.getString("status")), rs.getString("phone"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final AppUserRepository users;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CurrentContext context;
    private final AuditLog audit;
    private final PasswordEncoder passwords;
    private final AccountStatusCache accountStatuses;
    private final Clock clock;

    StaffAccountService(AppUserRepository users, EntityManager em, JdbcTemplate jdbc,
                        PlatformTransactionManager txManager, CurrentContext context, AuditLog audit,
                        PasswordEncoder passwords, AccountStatusCache accountStatuses, Clock clock) {
        this.users = users;
        this.em = em;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.context = context;
        this.audit = audit;
        this.passwords = passwords;
        this.accountStatuses = accountStatuses;
        this.clock = clock;
    }

    /** Dữ liệu tạo Nhân viên đã kiểm và chuẩn hóa; {@code phone} là {@code null} khi bỏ trống. */
    record NewStaff(String loginKey, String displayName, String password, String phone) {

        @Override
        public String toString() {
            return "NewStaff[loginKey=" + loginKey + ", displayName=" + displayName + "]";
        }
    }

    /**
     * Mọi người dùng của Cửa hàng (cả Chủ cửa hàng), Chủ cửa hàng đứng đầu, rồi Nhân viên mới tạo trước. {@code page}
     * từ 0, {@code size} mặc định 20, tối đa 100 (AD-19).
     */
    @Transactional(readOnly = true)
    PageResponse<StoreUser> list(Integer page, Integer size) {
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = PageResponse.clampSize(size);
        UUID storeId = context.storeId();
        Long total = jdbc.queryForObject("select count(*) from app.app_user where store_id = ?", Long.class, storeId);
        if (total == null || total == 0) {
            return new PageResponse<>(List.of(), 0, pageNo, pageSize);
        }
        List<StoreUser> items = jdbc.query("""
                select id, login_key, display_name, role, status, phone, created_at
                from app.app_user
                where store_id = ?
                order by case when role = 'OWNER' then 0 else 1 end, created_at desc, id desc
                limit ? offset ?
                """, ROW, storeId, pageSize, (long) pageNo * pageSize);
        return new PageResponse<>(items, total, pageNo, pageSize);
    }

    /** Gọi ngoài transaction: kiểm và băm mật khẩu trước, transaction chỉ bọc INSERT và Nhật ký. */
    StoreUser create(CreateStaffRequest request) {
        NewStaff staff = validate(request);
        String hash = passwords.encode(staff.password());
        return tx.execute(status -> {
            // Id đặt sẵn nên persist (INSERT thẳng), không qua merge của save()
            AppUserEntity user = new AppUserEntity(UUID.randomUUID(), staff.loginKey(), staff.displayName(), hash,
                    Role.STAFF.name(), staff.phone(), now());
            try {
                em.persist(user);
                em.flush();
            } catch (PersistenceException | DataIntegrityViolationException e) {
                // AD-9: unique toàn hệ thống trên login_key, không SELECT kiểm tra trước. Cả transaction rollback.
                throw isLoginKeyTaken(e) ? loginTaken() : e;
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("loginKey", user.loginKey());
            after.put("displayName", user.displayName());
            after.put("role", user.role());
            after.put("status", user.status());
            if (user.phone() != null) {
                after.put("phone", user.phone());
            }
            audit.record(AuditAction.PLATFORM_USER_CREATE, user.id().toString(), null, after);
            return StoreUser.of(user);
        });
    }

    StoreUser lock(UUID id) {
        return changeStatus(id, UserStatus.LOCKED, AuditAction.PLATFORM_USER_LOCK);
    }

    StoreUser unlock(UUID id) {
        return changeStatus(id, UserStatus.ACTIVE, AuditAction.PLATFORM_USER_UNLOCK);
    }

    /**
     * Chủ cửa hàng đặt mật khẩu mới cho Nhân viên (không cần mật khẩu cũ). Dữ liệu sai báo {@code 400} trước
     * {@code 404}, {@code 409}. Tài khoản đang bị khóa vẫn đặt lại được (khóa giữ nguyên).
     */
    void resetPassword(UUID id, ResetPasswordRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        String password = AccountFields.password(request.password(), "password", "Mật khẩu", errors);
        throwIfAny(errors);
        String hash = passwords.encode(password);   // băm (chậm) trước khi mở transaction
        tx.executeWithoutResult(status -> {
            AppUserEntity user = staffForUpdate(id);
            user.changePassword(hash, now());
            users.flush();
            audit.record(AuditAction.PLATFORM_USER_RESET_PASSWORD, id.toString(), null, null);
        });
    }

    /**
     * Đổi trạng thái trong transaction riêng; commit xong thì bỏ trạng thái đã cache (kể cả khi không đổi gì: cache có
     * thể đang giữ trạng thái cũ), để phiên đang mở thấy trạng thái mới ngay ở request kế tiếp.
     */
    private StoreUser changeStatus(UUID id, UserStatus next, AuditAction action) {
        StoreUser result = tx.execute(status -> {
            AppUserEntity user = staffForUpdate(id);
            String before = user.status();
            if (user.changeStatus(next, now())) {
                users.flush();
                audit.record(action, id.toString(), Map.of("status", before), Map.of("status", next.name()));
            }
            return StoreUser.of(user);
        });
        accountStatuses.evict(id);
        return result;
    }

    /** Nhân viên của Cửa hàng hiện tại, đã khóa dòng. Không có (hoặc của Cửa hàng khác): 404; Chủ cửa hàng: 409. */
    private AppUserEntity staffForUpdate(UUID id) {
        AppUserEntity user = users.findForUpdate(id).orElseThrow(StaffAccountService::notFound);
        if (!Role.STAFF.name().equals(user.role())) {
            throw ownerProtected();
        }
        return user;
    }

    /** Mọi trường trong một lần: {@code 400 /errors/common/validation} với {@code errors[]} theo trường. */
    static NewStaff validate(CreateStaffRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        String loginKey = checkLoginKey(request.loginKey(), errors);

        String displayName = AccountFields.displayName(request.displayName(), "Tên Nhân viên", errors);
        String password = AccountFields.password(request.password(), "password", "Mật khẩu", errors);

        String phone = AccountFields.vietnamPhone(request.phone(), errors);

        throwIfAny(errors);
        return new NewStaff(loginKey, displayName, password, phone);
    }

    /** Tên đăng nhập đã chuẩn hóa (chữ thường), hoặc ghi lỗi trên {@code loginKey}. */
    private static String checkLoginKey(String raw, List<Map<String, String>> errors) {
        // Cắt như LoginKeys.normalize (trim), để tên gõ lúc đăng nhập khớp tên đã lưu
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            addError(errors, "loginKey", "Nhập tên đăng nhập");
        } else if (trimmed.indexOf('@') >= 0 || LoginKeys.isOwnerShaped(trimmed)) {
            addError(errors, "loginKey",
                    "Tên đăng nhập không được là số điện thoại hay email (dành cho Chủ cửa hàng)");
        } else if (!LOGIN_KEY_CHARS.matcher(trimmed).matches()) {
            addError(errors, "loginKey", "Tên đăng nhập chỉ gồm chữ cái không dấu, chữ số và các dấu . _ -, "
                    + "bắt đầu bằng chữ cái hoặc chữ số");
        } else if (trimmed.length() < LOGIN_KEY_MIN || trimmed.length() > LOGIN_KEY_MAX) {
            addError(errors, "loginKey", "Tên đăng nhập từ " + LOGIN_KEY_MIN + " đến " + LOGIN_KEY_MAX + " ký tự");
        } else {
            return LoginKeys.normalize(trimmed);   // chỉ ASCII nên chữ thường không đổi độ dài
        }
        return null;
    }

    /** Mốc giờ từ {@code Clock}, cắt tới micro giây như {@code timestamptz}. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Lỗi ghi là do đúng unique {@code app_user_login_key_uq}: tin tên constraint mà Hibernate đã tách; không có thì
     * chỉ xét dòng đầu câu lỗi của PostgreSQL (dòng {@code Detail} chứa tên đăng nhập người dùng nhập).
     */
    static boolean isLoginKeyTaken(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return LOGIN_KEY_UNIQUE.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        String quoted = '"' + LOGIN_KEY_UNIQUE + '"';
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.lines().findFirst().orElse("").contains(quoted)) {
                return true;
            }
        }
        return false;
    }

    private static ProblemException loginTaken() {
        return ProblemException.conflict(LOGIN_TAKEN, "Tên đăng nhập đã được dùng",
                "Tên đăng nhập này đã có người dùng (tên đăng nhập là duy nhất trên toàn hệ thống). Hãy chọn tên khác.");
    }

    private static ProblemException ownerProtected() {
        return ProblemException.conflict(OWNER_PROTECTED, "Không áp dụng cho Chủ cửa hàng",
                "Chỉ khóa, mở khóa hay đặt lại mật khẩu được cho tài khoản Nhân viên, không cho Chủ cửa hàng.");
    }

    private static ProblemException notFound() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy",
                "Không tìm thấy tài khoản Nhân viên này.");
    }
}
