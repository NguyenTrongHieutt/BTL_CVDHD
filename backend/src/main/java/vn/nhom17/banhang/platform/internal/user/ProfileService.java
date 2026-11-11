package vn.nhom17.banhang.platform.internal.user;

import static vn.nhom17.banhang.platform.internal.user.AccountFields.addError;
import static vn.nhom17.banhang.platform.internal.user.AccountFields.throwIfAny;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.internal.store.StoreText;
import vn.nhom17.banhang.platform.internal.user.ProfileController.ChangePasswordRequest;
import vn.nhom17.banhang.platform.internal.user.ProfileController.UpdateProfileRequest;

/**
 * Thông tin cá nhân của người đang đăng nhập (FR-47, TK-04), cả Chủ cửa hàng lẫn Nhân viên. Người dùng lấy từ phiên
 * ({@link CurrentContext#userId()}), đọc qua entity có {@code @TenantId} (và RLS) nên chỉ thấy tài khoản của Cửa hàng
 * trong token; tài khoản không còn thì {@code 401 /errors/common/unauthorized} như {@code GET /me}.
 *
 * <ul>
 *   <li><b>Sửa hồ sơ:</b> {@code displayName} và {@code phone} kiểm ở {@link AccountFields} (tên như tên Nhân viên,
 *       số như số Khách hàng KH-01, lưu dạng {@code 0…}), mọi lỗi trong một lần. Số đang lưu từ trước quy tắc này
 *       (Nhân viên tạo trước TK-04 có thể có số bàn như {@code 028 1234 5678}) gửi lại y nguyên thì giữ nguyên, để vẫn
 *       sửa được tên. Không đổi {@code login_key}, {@code email}, vai trò. Giống giá trị đang lưu thì không ghi gì.
 *       Không ghi Nhật ký.</li>
 *   <li><b>Đổi mật khẩu:</b> cần mật khẩu hiện tại. Sai: {@code 422 /errors/platform/wrong-current-password} (không
 *       dùng 401, để frontend không coi là hết phiên), không đổi gì. Mật khẩu mới theo cùng quy tắc với tạo Nhân viên
 *       và phải khác mật khẩu hiện tại ({@code 400} trên {@code newPassword}); lỗi nhập liệu báo trước {@code 422}.
 *       Không thu hồi token: phiên hiện tại (và phiên khác đang mở) vẫn dùng được. Không ghi Nhật ký (không chứa mật
 *       khẩu dù sao).</li>
 *   <li>Kiểm mật khẩu hiện tại và băm mật khẩu mới (bcrypt, chậm) chạy NGOÀI transaction; transaction ghi khóa dòng
 *       ({@code FOR UPDATE}) và chỉ ghi khi băm đang lưu vẫn là băm vừa kiểm: mật khẩu đã đổi ở nơi khác giữa hai bước
 *       (lần đổi cùng lúc, Chủ cửa hàng đặt lại) thì mật khẩu vừa kiểm không còn là mật khẩu hiện tại → {@code 422}.</li>
 * </ul>
 */
@Service
class ProfileService {

    static final String WRONG_CURRENT_PASSWORD = "/errors/platform/wrong-current-password";

    private final AppUserRepository users;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final CurrentContext context;
    private final PasswordEncoder passwords;
    private final Clock clock;

    ProfileService(AppUserRepository users, PlatformTransactionManager txManager, CurrentContext context,
                   PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.tx = new TransactionTemplate(txManager);
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
        this.context = context;
        this.passwords = passwords;
        this.clock = clock;
    }

    Profile get() {
        UUID userId = context.userId();
        return readOnly.execute(status -> Profile.of(users.findById(userId).orElseThrow(ProfileService::accountGone)));
    }

    /**
     * Khóa dòng rồi kiểm và ghi trong cùng transaction (hai lần sửa cùng lúc chạy lần lượt): kiểm số điện thoại cần số
     * đang lưu. Dữ liệu sai thì transaction rollback, không ghi gì.
     */
    Profile update(UpdateProfileRequest request) {
        UUID userId = context.userId();
        return tx.execute(status -> {
            AppUserEntity user = users.findForUpdate(userId).orElseThrow(ProfileService::accountGone);
            List<Map<String, String>> errors = new ArrayList<>();
            String displayName = AccountFields.displayName(request.displayName(), "Tên hiển thị", errors);
            String phone = phoneToSave(request.phone(), user.phone(), errors);
            throwIfAny(errors);
            if (user.changeProfile(displayName, phone, now())) {
                users.flush();
            }
            return Profile.of(user);
        });
    }

    void changePassword(ChangePasswordRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        String current = request.currentPassword();
        if (current == null || current.trim().isEmpty()) {
            addError(errors, "currentPassword", "Nhập mật khẩu hiện tại");
            current = null;
        }
        String next = AccountFields.password(request.newPassword(), "newPassword", "Mật khẩu mới", errors);
        if (next != null && next.equals(current)) {
            addError(errors, "newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        throwIfAny(errors);

        UUID userId = context.userId();
        String checkedHash = readOnly.execute(status ->
                users.findById(userId).orElseThrow(ProfileService::accountGone).passwordHash());
        // Dài hơn 72 byte thì không thể là mật khẩu đã đặt; không đưa cho bcrypt (chỉ so 72 byte đầu)
        if (AccountFields.longerThanAnyStoredPassword(current) || !passwords.matches(current, checkedHash)) {
            throw wrongCurrentPassword();
        }
        String newHash = passwords.encode(next);
        tx.executeWithoutResult(status -> {
            AppUserEntity user = users.findForUpdate(userId).orElseThrow(ProfileService::accountGone);
            if (!user.passwordHash().equals(checkedHash)) {
                throw wrongCurrentPassword();
            }
            user.changePassword(newHash, now());
            users.flush();
        });
    }

    /**
     * Số mới theo {@link AccountFields#vietnamPhone}; riêng số đang lưu gửi lại y nguyên (sau chuẩn hóa văn bản) thì giữ
     * nguyên dù không theo quy tắc đó (dòng cũ).
     */
    private static String phoneToSave(String raw, String stored, List<Map<String, String>> errors) {
        if (stored != null && StoreText.normalize(raw).equals(stored)) {
            return stored;
        }
        return AccountFields.vietnamPhone(raw, errors);
    }

    /** Mốc giờ từ {@code Clock}, cắt tới micro giây như {@code timestamptz}. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ProblemException wrongCurrentPassword() {
        return ProblemException.unprocessable(WRONG_CURRENT_PASSWORD, "Mật khẩu hiện tại không đúng",
                "Mật khẩu hiện tại không đúng nên mật khẩu chưa được đổi. Hãy nhập lại.");
    }

    /** Như {@code GET /me}: token hợp lệ nhưng tài khoản không còn trong Cửa hàng của token. */
    private static ProblemException accountGone() {
        return new ProblemException(HttpStatus.UNAUTHORIZED, "/errors/common/unauthorized", "Chưa đăng nhập",
                "Tài khoản không còn tồn tại.");
    }
}
