package vn.nhom17.banhang.catalog.internal.customer;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.catalog.CustomerLookup;
import vn.nhom17.banhang.catalog.internal.Constraints;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.SearchText;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.customer.CustomerController.CustomerRequest;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Hồ sơ Khách hàng (FR-18, KH-01) và tìm kiếm (FR-19, KH-02).
 *
 * <ul>
 *   <li>Số điện thoại theo {@link CustomerPhones} (lưu dạng {@code 0…}); tên tối đa {@value #NAME_MAX}, ghi chú tối đa
 *       {@value #NOTE_MAX} ký tự (được xuống dòng), chuẩn hóa bằng {@link TextFields} như Danh mục. Trống là
 *       {@code null}.</li>
 *   <li>Trùng số trong Cửa hàng (Khách hàng chưa xóa mềm) do unique {@code customer_phone_uq} bắt, không SELECT kiểm
 *       trước: {@code 409 /errors/catalog/customer-phone-taken} kèm {@code existingCustomerId} (id Khách hàng đang
 *       dùng số đó, đọc lại trong transaction mới sau khi transaction ghi đã rollback). Cửa hàng khác dùng cùng số thì
 *       được.</li>
 *   <li>Id của Cửa hàng khác, không có, hoặc đã xóa mềm: {@code 404 /errors/common/not-found} (AD-5).</li>
 *   <li>Không có khóa lạc quan (bảng {@code customer} không có cột phiên bản): hai người sửa cùng Khách hàng thì lần
 *       ghi sau thắng (last-write-wins).</li>
 * </ul>
 *
 * <p>{@link #create} và {@link #update} gọi từ controller, NGOÀI transaction: chúng tự mở transaction ghi
 * ({@link TransactionTemplate}) để còn đọc được {@code existingCustomerId} sau lỗi unique.
 */
@Service
class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    static final int NAME_MAX = 100;
    static final int NOTE_MAX = 500;
    static final String PHONE_UNIQUE = "customer_phone_uq";
    static final String PHONE_TAKEN = "/errors/catalog/customer-phone-taken";
    /** Trường mở rộng của Problem khi trùng số: id Khách hàng đã có, để frontend mở luôn. */
    static final String EXISTING_CUSTOMER_ID = "existingCustomerId";

    private static final TextFields.Rule NAME = TextFields.Rule.optional("Tên Khách hàng", NAME_MAX);
    private static final TextFields.Rule NOTE = TextFields.Rule.optional("Ghi chú", NOTE_MAX).allowingLineBreaks();

    private static final RowMapper<CustomerView> ROW = (rs, i) -> new CustomerView(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("phone"), rs.getString("note"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    /**
     * Tên không dấu, không phân biệt hoa thường (chưa có tên thì xếp sau), rồi số điện thoại (duy nhất trong Cửa hàng);
     * {@code c.id} chốt cuối cho phân trang ổn định.
     */
    private static final String ORDER = "order by " + SearchText.folded("c.name") + " nulls last, c.phone, c.id\n";

    private final CustomerRepository customers;
    private final CustomerLookup lookup;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** Đọc {@code existingCustomerId} trong transaction riêng: transaction vừa gặp lỗi unique đã hỏng. */
    private final TransactionTemplate lookupTx;

    CustomerService(CustomerRepository customers, CustomerLookup lookup, JdbcTemplate jdbc, CurrentContext context,
                    Clock clock, PlatformTransactionManager txManager) {
        this.customers = customers;
        this.lookup = lookup;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.lookupTx = new TransactionTemplate(txManager);
        this.lookupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lookupTx.setReadOnly(true);
    }

    /** Hồ sơ đã kiểm và chuẩn hóa; {@code name}, {@code note} là {@code null} khi bỏ trống. */
    record CustomerData(String phone, String name, String note) {
    }

    /**
     * Danh sách có tìm kiếm (FR-19). {@code q} chuẩn hóa như tìm Sản phẩm ({@link SearchText#query}); rỗng thì bỏ qua.
     * Khớp khi tên chứa {@code q} (không dấu, không phân biệt hoa thường), hoặc số điện thoại chứa các chữ số của
     * {@code q} ({@link CustomerPhones#searchDigits}: {@code q} không có chữ cái, {@code +84} hiểu như {@code 0}).
     * Sắp theo tên không dấu (chưa có tên xếp sau), rồi số điện thoại. {@code page} từ 0, {@code size} mặc định 20,
     * tối đa 100 (AD-19).
     */
    @Transactional(readOnly = true)
    PageResponse<CustomerView> list(String q, Integer page, Integer size) {
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = PageResponse.clampSize(size);
        StringBuilder where = new StringBuilder("from app.customer c where c.store_id = ? and c.deleted_at is null\n");
        List<Object> args = new ArrayList<>(List.of(context.storeId()));
        String query = SearchText.query(q);
        if (query != null) {
            where.append(" and (").append(SearchText.containsFolded("c.name"));
            args.add(query);
            String digits = CustomerPhones.searchDigits(query);
            if (digits != null) {
                // Chỉ có chữ số nên không cần thoát ký tự của LIKE
                where.append("\n      or c.phone like ?");
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
        List<CustomerView> items = jdbc.query("select c.id, c.name, c.phone, c.note, c.created_at " + where + ORDER
                + "limit ? offset ?", ROW, args.toArray());
        return new PageResponse<>(items, total, pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    CustomerView get(UUID id) {
        return customers.findByIdAndDeletedAtIsNull(id).map(CustomerView::of).orElseThrow(CustomerService::notFound);
    }

    /** Thêm (FR-18; tạo nhanh FR-14 chỉ cần số điện thoại). Gọi ngoài transaction. */
    CustomerView create(CustomerRequest request) {
        CustomerData data = validate(request);
        try {
            return tx.execute(status -> {
                CustomerEntity customer = new CustomerEntity(data.phone(), data.name(), data.note(), now());
                customers.saveAndFlush(customer);
                return CustomerView.of(customer);
            });
        } catch (DataIntegrityViolationException e) {
            throw isPhoneTaken(e) ? phoneTaken(data.phone()) : e;
        }
    }

    /**
     * Sửa: thay toàn bộ hồ sơ ({@code name}, {@code note} thiếu hoặc rỗng thì xóa). Y nguyên thì không ghi gì. Đổi
     * sang số Khách hàng khác đang dùng: {@code 409 customer-phone-taken}. Không kiểm phiên bản: hai lần sửa cùng lúc
     * thì lần ghi sau thắng (last-write-wins). Gọi ngoài transaction.
     */
    CustomerView update(UUID id, CustomerRequest request) {
        CustomerData data = validate(request);
        try {
            return tx.execute(status -> {
                CustomerEntity customer = customers.findByIdAndDeletedAtIsNull(id)
                        .orElseThrow(CustomerService::notFound);
                if (customer.replace(data.phone(), data.name(), data.note(), now())) {
                    customers.flush();
                }
                return CustomerView.of(customer);
            });
        } catch (DataIntegrityViolationException e) {
            throw isPhoneTaken(e) ? phoneTaken(data.phone()) : e;
        }
    }

    /** Mọi trường trong một lần: {@code 400 /errors/common/validation} với {@code errors[]} theo trường. */
    static CustomerData validate(CustomerRequest request) {
        FieldErrors errors = new FieldErrors();
        String phone = null;
        if (CustomerPhones.isBlank(request.phone())) {
            errors.add("phone", "Nhập số điện thoại");
        } else {
            phone = CustomerPhones.normalize(request.phone());
            if (phone == null) {
                errors.add("phone", CustomerPhones.INVALID);
            }
        }
        String name = TextFields.check(request.name(), "name", NAME, errors);
        String note = TextFields.check(request.note(), "note", NOTE, errors);
        errors.throwIfAny();
        return new CustomerData(phone, emptyToNull(name), emptyToNull(note));
    }

    /** Mốc giờ từ {@code Clock}, cắt tới micro giây như {@code timestamptz}, để phản hồi khớp với lần đọc sau. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }

    private static boolean isPhoneTaken(Throwable error) {
        return Constraints.violated(error, PHONE_UNIQUE);
    }

    /**
     * {@code 409} kèm {@code existingCustomerId} của Khách hàng đang dùng số này. Không có trường này khi số vừa được
     * trả lại (Khách hàng kia đổi số ở yêu cầu khác), hoặc khi đọc lại lỗi: lỗi đọc lại không biến {@code 409} thành
     * {@code 500}.
     */
    private ProblemException phoneTaken(String phone) {
        UUID existing;
        try {
            existing = lookupTx.execute(status -> lookup.findByPhone(phone)
                    .map(CustomerLookup.CustomerSummary::customerId).orElse(null));
        } catch (RuntimeException lookupFailed) {
            log.warn("Không đọc được Khách hàng đang dùng số điện thoại trùng; trả 409 không kèm {}",
                    EXISTING_CUSTOMER_ID, lookupFailed);
            existing = null;
        }
        return new ProblemException(HttpStatus.CONFLICT, PHONE_TAKEN, "Số điện thoại đã có",
                "Cửa hàng đã có Khách hàng dùng số điện thoại này. Hãy mở Khách hàng đó thay vì thêm mới.",
                existing == null ? Map.of() : Map.of(EXISTING_CUSTOMER_ID, existing));
    }

    private static ProblemException notFound() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy",
                "Không tìm thấy Khách hàng này.");
    }
}
