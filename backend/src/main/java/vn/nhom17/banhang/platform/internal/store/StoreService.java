package vn.nhom17.banhang.platform.internal.store;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.internal.store.StoreController.StoreRequest;

/**
 * Xem, sửa Thông tin Cửa hàng (FR-48, TK-05).
 *
 * <ul>
 *   <li>Trường văn bản chuẩn hóa như ở Danh mục ({@link StoreText}: NFC, cắt khoảng trắng Unicode, chặn ký tự điều
 *       khiển); bỏ trống thì lưu {@code null}.</li>
 *   <li>Tài khoản ngân hàng: ba trường cùng trống (tắt chuyển khoản, AD-22) hoặc cùng có; thiếu một phần thì báo lỗi
 *       trên trường thiếu.</li>
 *   <li>Mọi lỗi nhập liệu trả một lần: {@code 400 /errors/common/validation} với {@code errors[]} theo trường, không
 *       đổi gì.</li>
 *   <li>Khóa dòng {@code app.store} rồi so với giá trị đang lưu: có thay đổi thì UPDATE và ghi Nhật ký
 *       {@link AuditAction#PLATFORM_STORE_UPDATE} (chỉ các trường đã đổi; số tài khoản chỉ giữ 4 số cuối) trong CÙNG
 *       transaction; không đổi gì thì không ghi gì.</li>
 * </ul>
 */
@Service
class StoreService {

    static final int NAME_MAX = 200;
    static final int PHONE_MAX = 20;
    static final int ADDRESS_MAX = 300;
    static final int INVOICE_FOOTER_MAX = 300;
    static final int ACCOUNT_NAME_MAX = 50;
    static final int EXPIRY_WARNING_DAYS_MAX = 365;
    /** Tiền lớn nhất frontend còn biểu diễn đúng bằng số JavaScript ({@code Number.MAX_SAFE_INTEGER}). */
    static final long MONEY_MAX = 9_007_199_254_740_991L;

    private static final Pattern PHONE_CHARS = Pattern.compile("[0-9 +.\\-]+");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");
    private static final Pattern BANK_BIN = Pattern.compile("[0-9]{6}");
    private static final Pattern ACCOUNT_NO = Pattern.compile("[0-9]{6,19}");
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Z ]+");
    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final AuditLog audit;
    private final Clock clock;

    StoreService(JdbcTemplate jdbc, CurrentContext context, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    StoreView get() {
        return StoreView.of(jdbc.queryForObject(StoreRows.SELECT_BY_ID, StoreRows::map, context.storeId()));
    }

    @Transactional
    StoreView update(StoreRequest request) {
        StoreView next = validate(request);
        UUID storeId = context.storeId();
        // Khóa dòng: hai lần lưu cùng lúc thì lần sau so với giá trị lần trước đã ghi, Nhật ký không lệch
        StoreView current = StoreView.of(jdbc.queryForObject(StoreRows.SELECT_BY_ID + " for update",
                StoreRows::map, storeId));

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        Map<String, Object> wanted = next.fields();
        current.fields().forEach((field, value) -> {
            Object changed = wanted.get(field);
            if (!Objects.equals(value, changed)) {
                before.put(field, auditValue(field, value));
                after.put(field, auditValue(field, changed));
            }
        });
        if (before.isEmpty()) {
            return current;
        }
        jdbc.update("""
                update app.store
                set name = ?, phone = ?, address = ?, invoice_footer = ?,
                    bank_bin = ?, bank_account_no = ?, bank_account_name = ?,
                    loyalty_earn_amount = ?, loyalty_point_value = ?, expiry_warning_days = ?, updated_at = ?
                where id = ?
                """, next.name(), next.phone(), next.address(), next.invoiceFooter(), next.bankBin(),
                next.bankAccountNo(), next.bankAccountName(), next.loyaltyEarnAmount(), next.loyaltyPointValue(),
                next.expiryWarningDays(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), storeId);
        audit.record(AuditAction.PLATFORM_STORE_UPDATE, storeId.toString(), before, after);
        return next;
    }

    /** Nhật ký không giữ đủ số tài khoản ngân hàng: chỉ 4 số cuối ({@code ****4567}); số tài khoản có ít nhất 6 số. */
    static Object auditValue(String field, Object value) {
        if (field.equals("bankAccountNo") && value instanceof String accountNo) {
            return "****" + accountNo.substring(Math.max(0, accountNo.length() - 4));
        }
        return value;
    }

    /** Chuẩn hóa và kiểm mọi trường; có lỗi thì ném {@code 400} với mọi lỗi trong một lần. */
    static StoreView validate(StoreRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();

        String name = StoreText.normalize(request.name());
        if (StoreText.hasInvalidChars(name)) {
            addError(errors, "name", "Tên Cửa hàng có ký tự không hợp lệ");
        } else if (name.isEmpty()) {
            addError(errors, "name", "Nhập tên Cửa hàng");
        } else if (StoreText.length(name) > NAME_MAX) {
            addError(errors, "name", "Tên Cửa hàng tối đa " + NAME_MAX + " ký tự");
        }

        String phone = StoreText.normalize(request.phone());
        if (!phone.isEmpty()) {
            if (!PHONE_CHARS.matcher(phone).matches()) {
                addError(errors, "phone", "Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . -");
            } else if (!HAS_DIGIT.matcher(phone).matches()) {
                addError(errors, "phone", "Số điện thoại phải có chữ số");
            } else if (StoreText.length(phone) > PHONE_MAX) {
                addError(errors, "phone", "Số điện thoại tối đa " + PHONE_MAX + " ký tự");
            }
        }

        String address = optionalText(request.address(), "address", "Địa chỉ", ADDRESS_MAX, errors);
        String invoiceFooter = optionalText(request.invoiceFooter(), "invoiceFooter", "Chân hóa đơn",
                INVOICE_FOOTER_MAX, errors);

        String bankBin = StoreText.normalize(request.bankBin());
        String accountNo = StoreText.normalize(request.bankAccountNo());
        String accountName = SPACES.matcher(StoreText.normalize(request.bankAccountName())).replaceAll(" ");
        boolean hasBankAccount = !bankBin.isEmpty() || !accountNo.isEmpty() || !accountName.isEmpty();
        if (hasBankAccount) {
            if (bankBin.isEmpty()) {
                addError(errors, "bankBin", "Chọn ngân hàng");
            } else if (!BANK_BIN.matcher(bankBin).matches()) {
                addError(errors, "bankBin", "Mã ngân hàng (BIN) gồm đúng 6 chữ số");
            }
            if (accountNo.isEmpty()) {
                addError(errors, "bankAccountNo", "Nhập số tài khoản");
            } else if (!ACCOUNT_NO.matcher(accountNo).matches()) {
                addError(errors, "bankAccountNo", "Số tài khoản gồm 6 đến 19 chữ số");
            }
            if (accountName.isEmpty()) {
                addError(errors, "bankAccountName", "Nhập tên chủ tài khoản");
            } else if (!ACCOUNT_NAME.matcher(accountName).matches()) {
                addError(errors, "bankAccountName", "Tên chủ tài khoản chỉ gồm chữ in hoa không dấu và khoảng trắng");
            } else if (StoreText.length(accountName) > ACCOUNT_NAME_MAX) {
                addError(errors, "bankAccountName", "Tên chủ tài khoản tối đa " + ACCOUNT_NAME_MAX + " ký tự");
            }
        }

        long earnAmount = wholeNumber(request.loyaltyEarnAmount(), "loyaltyEarnAmount", "Số tiền để được 1 điểm", 1,
                MONEY_MAX, "Số tiền để được 1 điểm quá lớn", errors);
        long pointValue = wholeNumber(request.loyaltyPointValue(), "loyaltyPointValue", "Giá trị 1 điểm", 0,
                MONEY_MAX, "Giá trị 1 điểm quá lớn", errors);
        long warningDays = wholeNumber(request.expiryWarningDays(), "expiryWarningDays", "Số ngày cảnh báo hết hạn", 0,
                EXPIRY_WARNING_DAYS_MAX, "Số ngày cảnh báo hết hạn tối đa " + EXPIRY_WARNING_DAYS_MAX + " ngày",
                errors);

        if (!errors.isEmpty()) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                    "Vui lòng kiểm tra lại các trường được đánh dấu.", Map.of("errors", List.copyOf(errors)));
        }
        return new StoreView(name, emptyToNull(phone), address, invoiceFooter,
                hasBankAccount ? bankBin : null, hasBankAccount ? accountNo : null,
                hasBankAccount ? accountName : null, earnAmount, pointValue, (int) warningDays);
    }

    /** Trường văn bản không bắt buộc: chuẩn hóa, bỏ trống thì {@code null}; sai thì ghi lỗi và trả {@code null}. */
    private static String optionalText(String raw, String field, String label, int max,
                                       List<Map<String, String>> errors) {
        String text = StoreText.normalize(raw);
        if (StoreText.hasInvalidChars(text)) {
            addError(errors, field, label + " có ký tự không hợp lệ");
        } else if (StoreText.length(text) > max) {
            addError(errors, field, label + " tối đa " + max + " ký tự");
        }
        return emptyToNull(text);
    }

    /**
     * Số nguyên bắt buộc từ {@code min} (0 hoặc 1) đến {@code max}. Sai thì ghi lỗi và trả {@code -1}. Nhận
     * {@link BigDecimal} để {@code 1.5} báo lỗi thay vì bị cắt thành 1; {@code 1500.0} được nhận.
     */
    private static long wholeNumber(BigDecimal raw, String field, String label, long min, long max,
                                    String tooLarge, List<Map<String, String>> errors) {
        if (raw == null) {
            addError(errors, field, "Nhập " + lowerFirst(label));
            return -1;
        }
        if (raw.stripTrailingZeros().scale() > 0) {
            addError(errors, field, label + " phải là số nguyên");
            return -1;
        }
        if (raw.compareTo(BigDecimal.valueOf(min)) < 0) {
            addError(errors, field, min > 0 ? label + " phải lớn hơn 0" : label + " không được âm");
            return -1;
        }
        if (raw.compareTo(BigDecimal.valueOf(max)) > 0) {
            addError(errors, field, tooLarge);
            return -1;
        }
        return raw.longValueExact();
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }

    private static String lowerFirst(String label) {
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

    private static void addError(List<Map<String, String>> errors, String field, String message) {
        errors.add(Map.of("field", field, "message", message));
    }
}
