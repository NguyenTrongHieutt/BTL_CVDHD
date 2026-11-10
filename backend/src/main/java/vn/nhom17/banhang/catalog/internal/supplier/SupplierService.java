package vn.nhom17.banhang.catalog.internal.supplier;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.internal.Constraints;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.SearchText;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.supplier.SupplierController.SupplierRequest;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Quản lý Nhà cung cấp (FR-23, KHO-02): xem, tìm, thêm, sửa.
 *
 * <ul>
 *   <li>Chuỗi chuẩn hóa bằng {@link TextFields} như tên Danh mục (bỏ ký tự định dạng vô hình, khoảng trắng Unicode
 *       thành dấu cách, NFC, cắt hai đầu, độ dài theo code point; từ chối ký tự điều khiển). Tên bắt buộc, tối đa
 *       {@value #NAME_MAX}; mã tối đa {@value #CODE_MAX}, chỉ chữ cái không dấu, chữ số, {@code -}, {@code _}, bắt
 *       đầu bằng chữ cái hoặc chữ số, lưu chữ HOA; số điện thoại tối đa {@value #PHONE_MAX}, chỉ chữ số, khoảng trắng,
 *       {@code + . - ( )}, phải có chữ số (như Thông tin Cửa hàng); địa chỉ tối đa {@value #ADDRESS_MAX}; ghi chú tối
 *       đa {@value #NOTE_MAX} (được xuống dòng). Trường tùy chọn bỏ trống là {@code null}.</li>
 *   <li>Trùng mã trong Cửa hàng (Nhà cung cấp chưa xóa mềm) do unique {@code supplier_code_uq} bắt, không SELECT kiểm
 *       trước: {@code 409 /errors/catalog/supplier-code-taken}. Mã lưu chữ HOA nên khác hoa thường vẫn là trùng. Cửa
 *       hàng khác dùng cùng mã thì được; trùng tên thì được.</li>
 *   <li>Id của Cửa hàng khác, không có, hoặc đã xóa mềm: {@code 404 /errors/common/not-found} (AD-5).</li>
 *   <li>Không có khóa lạc quan (bảng {@code supplier} không có cột phiên bản): hai lần sửa cùng lúc thì lần ghi sau
 *       thắng (last-write-wins).</li>
 * </ul>
 */
@Service
class SupplierService {

    static final int NAME_MAX = 200;
    static final int CODE_MAX = 30;
    static final int PHONE_MAX = 20;
    static final int ADDRESS_MAX = 300;
    static final int NOTE_MAX = 500;
    static final String CODE_UNIQUE = "supplier_code_uq";
    static final String CODE_TAKEN = "/errors/catalog/supplier-code-taken";
    /** Câu lỗi khi mã có ký tự ngoài chữ cái không dấu, chữ số, {@code -}, {@code _}, hoặc không bắt đầu đúng. */
    static final String CODE_INVALID =
            "Mã Nhà cung cấp chỉ gồm chữ cái không dấu, chữ số, dấu - và dấu _, bắt đầu bằng chữ cái hoặc chữ số";
    static final String PHONE_CHARS_INVALID = "Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . - ( )";
    static final String PHONE_NO_DIGIT = "Số điện thoại phải có chữ số";
    static final String SORT_INVALID = "Chỉ sắp xếp được theo name hoặc createdAt, kèm asc hoặc desc";

    private static final TextFields.Rule NAME = TextFields.Rule.required("Tên Nhà cung cấp", NAME_MAX);
    private static final TextFields.Rule CODE = TextFields.Rule.optional("Mã Nhà cung cấp", CODE_MAX);
    private static final TextFields.Rule PHONE = TextFields.Rule.optional("Số điện thoại", PHONE_MAX);
    private static final TextFields.Rule ADDRESS = TextFields.Rule.optional("Địa chỉ", ADDRESS_MAX);
    private static final TextFields.Rule NOTE = TextFields.Rule.optional("Ghi chú", NOTE_MAX).allowingLineBreaks();
    /** Mã hợp lệ; cũng là điều kiện để {@code q} được so với đầu mã (chỉ ASCII nên viết HOA không đổi chữ). */
    private static final Pattern CODE_CHARS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern PHONE_CHARS = Pattern.compile("[0-9 +.\\-()]+");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");
    private static final Pattern NOT_DIGIT = Pattern.compile("[^0-9]");

    /** Chữ số của số điện thoại đã lưu, {@code 84} ở đầu (mã nước) đổi thành {@code 0}, như {@link #searchDigits}. */
    private static final String PHONE_DIGITS = "regexp_replace(regexp_replace(s.phone, '[^0-9]', '', 'g'), '^84', '0')";

    private static final RowMapper<SupplierView> ROW = (rs, i) -> new SupplierView(rs.getObject("id", UUID.class),
            rs.getString("code"), rs.getString("name"), rs.getString("phone"), rs.getString("address"),
            rs.getString("note"));

    private final SupplierRepository suppliers;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    SupplierService(SupplierRepository suppliers, JdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.suppliers = suppliers;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    /** Dữ liệu đã kiểm và chuẩn hóa; trường tùy chọn là {@code null} khi bỏ trống, {@code code} chữ HOA. */
    record SupplierData(String name, String code, String phone, String address, String note) {
    }

    /**
     * Danh sách có tìm kiếm. {@code q} chuẩn hóa như tìm Sản phẩm ({@link SearchText#query}); rỗng thì bỏ qua. Khớp
     * khi tên chứa {@code q} (không dấu, không phân biệt hoa thường; {@code %}, {@code _}, {@code \} là chữ thường),
     * hoặc, khi {@code q} có dạng mã, mã bắt đầu bằng {@code q} (không phân biệt hoa thường), hoặc, khi {@code q}
     * không có chữ cái và có chữ số, số điện thoại chứa các chữ số của {@code q} (chỉ so chữ số, {@code 84} ở đầu
     * hiểu như {@code 0} ở cả hai phía: {@code "1234 5678"}, {@code "+84 28 1234"} khớp {@code "028 1234 5678"}).
     * {@code sort}: {@code name} (mặc định: tên không dấu, rồi mã) hoặc {@code createdAt}, kèm {@code ,asc} (mặc
     * định) hay {@code ,desc}; giá trị khác là {@code 400} trên {@code sort}. {@code page} từ 0, {@code size} mặc
     * định 20, tối đa 100 (AD-19).
     */
    @Transactional(readOnly = true)
    PageResponse<SupplierView> list(String q, Integer page, Integer size, String sort) {
        FieldErrors errors = new FieldErrors();
        String order = orderBy(sort, errors);
        errors.throwIfAny();
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = PageResponse.clampSize(size);
        StringBuilder where = new StringBuilder("from app.supplier s where s.store_id = ? and s.deleted_at is null\n");
        List<Object> args = new ArrayList<>(List.of(context.storeId()));
        String query = SearchText.query(q);
        if (query != null) {
            where.append(" and (").append(SearchText.containsFolded("s.name"));
            args.add(query);
            if (CODE_CHARS.matcher(query).matches()) {
                // q chỉ ASCII nên viết HOA như mã đã lưu (validate) mà không đổi chữ ("ß" không thành "SS")
                where.append("\n      or s.code like ? escape '\\'");
                args.add(SearchText.escapeLike(query.toUpperCase(Locale.ROOT)) + "%");
            }
            String digits = searchDigits(query);
            if (digits != null) {
                // Chỉ có chữ số nên không cần thoát ký tự của LIKE
                where.append("\n      or ").append(PHONE_DIGITS).append(" like ?");
                args.add("%" + digits + "%");
            }
            where.append(")\n");
        }
        Long total = jdbc.queryForObject("select count(*) " + where, Long.class, args.toArray());
        if (total == null || total == 0) {
            return new PageResponse<>(List.of(), 0, pageNo, pageSize);
        }
        args.add(pageSize);
        args.add((long) pageNo * pageSize);
        List<SupplierView> items = jdbc.query("select s.id, s.code, s.name, s.phone, s.address, s.note " + where
                + order + "limit ? offset ?", ROW, args.toArray());
        return new PageResponse<>(items, total, pageNo, pageSize);
    }

    /**
     * {@code ORDER BY} theo {@code sort=<field>[,asc|desc]}, luôn chốt bằng {@code s.id} để phân trang ổn định. Bỏ
     * trống: tên không dấu tăng dần, cùng tên thì theo mã (chưa có mã xếp sau). Sai thì ghi lỗi trên {@code sort}.
     */
    static String orderBy(String raw, FieldErrors errors) {
        String[] parts = raw == null || raw.isBlank() ? new String[] {"name"} : raw.split(",", -1);
        String field = parts[0].strip();
        String dir = parts.length == 2 ? parts[1].strip().toLowerCase(Locale.ROOT) : "asc";
        if (parts.length > 2 || !(dir.equals("asc") || dir.equals("desc"))) {
            field = "";
        }
        return switch (field) {
            case "name" -> "order by " + SearchText.folded("s.name") + " " + dir
                    + ", s.code " + dir + " nulls last, s.id " + dir + "\n";
            case "createdAt" -> "order by s.created_at " + dir + ", s.id " + dir + "\n";
            default -> {
                errors.add("sort", SORT_INVALID);
                yield null;
            }
        };
    }

    @Transactional(readOnly = true)
    SupplierView get(UUID id) {
        return suppliers.findByIdAndDeletedAtIsNull(id).map(SupplierView::of).orElseThrow(SupplierService::notFound);
    }

    @Transactional
    SupplierView create(SupplierRequest request) {
        SupplierData data = validate(request);
        SupplierEntity supplier = new SupplierEntity(data, now());
        try {
            suppliers.saveAndFlush(supplier);
        } catch (DataIntegrityViolationException e) {
            throw isCodeTaken(e) ? codeTaken() : e;
        }
        return SupplierView.of(supplier);
    }

    /**
     * Sửa: thay toàn bộ (trường tùy chọn thiếu hoặc rỗng thì xóa). Y nguyên thì không ghi gì. Đổi sang mã Nhà cung
     * cấp khác đang dùng: {@code 409 supplier-code-taken}. Dữ liệu sai báo {@code 400} trước {@code 404}.
     */
    @Transactional
    SupplierView update(UUID id, SupplierRequest request) {
        SupplierData data = validate(request);
        SupplierEntity supplier = suppliers.findByIdAndDeletedAtIsNull(id).orElseThrow(SupplierService::notFound);
        if (supplier.replace(data, now())) {
            try {
                suppliers.flush();
            } catch (DataIntegrityViolationException e) {
                throw isCodeTaken(e) ? codeTaken() : e;
            }
        }
        return SupplierView.of(supplier);
    }

    /** Mọi trường trong một lần: {@code 400 /errors/common/validation} với {@code errors[]} theo trường. */
    static SupplierData validate(SupplierRequest request) {
        FieldErrors errors = new FieldErrors();
        String name = TextFields.check(request.name(), "name", NAME, errors);
        String code = TextFields.check(request.code(), "code", CODE, errors);
        if (code != null && !code.isEmpty()) {
            if (CODE_CHARS.matcher(code).matches()) {
                code = code.toUpperCase(Locale.ROOT);   // chỉ ASCII nên độ dài không đổi
            } else {
                errors.add("code", CODE_INVALID);
            }
        }
        String phone = TextFields.check(request.phone(), "phone", PHONE, errors);
        if (phone != null && !phone.isEmpty()) {
            if (!PHONE_CHARS.matcher(phone).matches()) {
                errors.add("phone", PHONE_CHARS_INVALID);
            } else if (!HAS_DIGIT.matcher(phone).matches()) {
                errors.add("phone", PHONE_NO_DIGIT);
            }
        }
        String address = TextFields.check(request.address(), "address", ADDRESS, errors);
        String note = TextFields.check(request.note(), "note", NOTE, errors);
        errors.throwIfAny();
        return new SupplierData(name, emptyToNull(code), emptyToNull(phone), emptyToNull(address), emptyToNull(note));
    }

    /**
     * Phần số điện thoại của từ khóa {@code query} (đã chuẩn hóa): các chữ số của nó, {@code 84} hay {@code +84} ở đầu
     * (mã nước) đổi thành {@code 0} như {@code CustomerPhones.searchDigits} ({@code "028 1234"} → {@code "0281234"},
     * {@code "+84 28 1234"} → {@code "0281234"}). Số đã lưu được đổi cùng cách trong SQL, nên hai cách viết khớp nhau.
     * {@code null} khi từ khóa có chữ cái (đang tìm theo tên, mã: "Kho 2" không khớp mọi số có chữ số 2) hoặc không có
     * chữ số nào.
     */
    static String searchDigits(String query) {
        if (query == null || query.codePoints().anyMatch(Character::isLetter)) {
            return null;
        }
        String digits = NOT_DIGIT.matcher(query).replaceAll("");
        if (digits.startsWith("84")) {
            digits = "0" + digits.substring(2);
        }
        return digits.isEmpty() ? null : digits;
    }

    /** Mốc giờ từ {@code Clock}, cắt tới micro giây như {@code timestamptz}. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }

    /** Lỗi unique đúng là {@code supplier_code_uq} (xem {@link Constraints}). */
    private static boolean isCodeTaken(Throwable error) {
        return Constraints.violated(error, CODE_UNIQUE);
    }

    private static ProblemException codeTaken() {
        return ProblemException.conflict(CODE_TAKEN, "Mã Nhà cung cấp đã có",
                "Cửa hàng đã có Nhà cung cấp dùng mã này (không phân biệt chữ hoa, chữ thường). Hãy nhập mã khác, "
                        + "hoặc để trống.");
    }

    private static ProblemException notFound() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy",
                "Không tìm thấy Nhà cung cấp này.");
    }
}
