package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.catalog.VariantDeactivated;
import vn.nhom17.banhang.catalog.internal.Constraints;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.category.CategoryRepository;
import vn.nhom17.banhang.catalog.internal.product.ProductController.CreateProductRequest;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Thêm, xem (FR-1, SP-01) và ngừng kinh doanh (FR-3, SP-03) Sản phẩm.
 *
 * <ul>
 *   <li>Tạo Sản phẩm cùng một Biến thể mặc định ({@code is_default}, {@code has_variants = false}) và phát
 *       {@link VariantCreated} trong CÙNG transaction, sau khi đã flush, để {@code inventory} nghe
 *       {@code BEFORE_COMMIT} tạo dòng {@code stock} và Tồn kho đầu kỳ (AD-14). Giá vốn ban đầu chỉ đi qua sự kiện,
 *       không lưu ở catalog (AD-15).</li>
 *   <li>Tạo Sản phẩm kèm Tồn kho đầu kỳ là một chứng từ {@code OPENING}, nên có {@code Idempotency-Key} (AD-7):
 *       {@link IdempotencyService} loại {@value #DOCUMENT_TYPE}. Gửi lại cùng khóa, cùng nội dung thì trả Sản phẩm cũ
 *       và không phát sự kiện lần hai.</li>
 *   <li>SKU và mã vạch lưu chữ HOA, nên khác hoa thường vẫn là trùng. Trùng trong Cửa hàng (Biến thể chưa xóa mềm)
 *       do unique bắt, không SELECT kiểm trước: {@code 409 barcode-taken} / {@code 409 sku-taken}. Cửa hàng khác
 *       dùng cùng mã thì được. SKU tự sinh thì kiểm trước và sinh lại cho tới khi còn trống.</li>
 *   <li>{@code categoryId} phải là Danh mục của Cửa hàng hiện tại (khóa ngoại không biết Cửa hàng, nên kiểm qua
 *       {@link CategoryRepository}); sai thì {@code 400} trên trường {@code categoryId}.</li>
 *   <li>Ngừng kinh doanh là một chiều (AD-14), không xóa hẳn, không đặt {@code deleted_at}: Sản phẩm đã ngừng vẫn
 *       đọc được qua chi tiết và danh sách (lịch sử Đơn hàng, báo cáo). Xem {@link #deactivate}.</li>
 * </ul>
 */
@Service
class ProductService {

    static final String DOCUMENT_TYPE = "CATALOG_PRODUCT";
    static final String DEFAULT_UNIT = "cái";
    static final String GENERATED_SKU_PREFIX = "SP-";
    /** Chặn vòng lặp vô tận khi sinh SKU; quá số lần này (thực tế không xảy ra) thì để unique báo {@code 409}. */
    static final int GENERATED_SKU_ATTEMPTS = 100;
    /** Tiền lớn nhất frontend còn biểu diễn đúng bằng số JavaScript ({@code Number.MAX_SAFE_INTEGER}). */
    static final long MONEY_MAX = 9_007_199_254_740_991L;

    static final String SKU_UNIQUE = "product_variant_sku_uq";
    static final String BARCODE_UNIQUE = "product_variant_barcode_uq";
    static final String CATEGORY_FK = "product_category_id_fkey";

    private static final TextFields.Rule NAME = TextFields.Rule.required("Tên Sản phẩm", 200);
    private static final TextFields.Rule UNIT = TextFields.Rule.optional("Đơn vị tính", 30);
    private static final TextFields.Rule DESCRIPTION = TextFields.Rule.optional("Mô tả", 2000).allowingLineBreaks();
    private static final TextFields.Rule SKU = TextFields.Rule.optional("SKU", 50);
    private static final TextFields.Rule BARCODE = TextFields.Rule.optional("Mã vạch", 50);
    private static final Pattern BARCODE_CHARS = Pattern.compile("[A-Za-z0-9]+");
    private static final String CATEGORY_MISSING = "Danh mục không tồn tại. Hãy chọn lại Danh mục.";
    /** Giá trị trả về của {@link #wholeNumber} khi đã ghi lỗi. */
    private static final long INVALID = -1;

    /** Chi tiết Sản phẩm: {@code %s} là câu chọn các dòng {@code app.product} (đã lọc, sắp, phân trang). */
    private static final String VIEW_SQL = """
            select p.id, p.name, p.category_id, c.name as category_name, p.unit, p.description, p.has_variants,
                   p.status, p.created_at, p.updated_at,
                   v.id as variant_id, v.sku, v.barcode, v.name as variant_name, v.sale_price, v.is_default,
                   v.status as variant_status
            from (%s) p
            left join app.category c on c.store_id = p.store_id and c.id = p.category_id
            left join app.product_variant v
                   on v.store_id = p.store_id and v.product_id = p.id and v.deleted_at is null
            order by p.created_at desc, p.id desc, v.is_default desc, v.created_at, v.id
            """;
    private static final String ONE_PRODUCT = """
            select * from app.product where store_id = ? and id = ? and deleted_at is null
            """;
    private static final String PAGE_OF_PRODUCTS = """
            select * from app.product where store_id = ? and deleted_at is null
            order by created_at desc, id desc limit ? offset ?
            """;

    private final EntityManager em;
    private final CategoryRepository categories;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final IdempotencyService idempotency;
    private final AuditLog audit;

    /** Nguồn id của Sản phẩm, Biến thể; test thay bằng dãy cố định để dựng SKU tự sinh bị trùng. */
    private final Supplier<UUID> ids;

    @Autowired
    ProductService(EntityManager em, CategoryRepository categories, JdbcTemplate jdbc, CurrentContext context,
                   Clock clock, ApplicationEventPublisher events, IdempotencyService idempotency, AuditLog audit) {
        this(em, categories, jdbc, context, clock, events, idempotency, audit, UUID::randomUUID);
    }

    ProductService(EntityManager em, CategoryRepository categories, JdbcTemplate jdbc, CurrentContext context,
                   Clock clock, ApplicationEventPublisher events, IdempotencyService idempotency, AuditLog audit,
                   Supplier<UUID> ids) {
        this.em = em;
        this.categories = categories;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
        this.events = events;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ids = ids;
    }

    /**
     * Nội dung đã kiểm và chuẩn hóa: đem băm vào {@code request_hash} và dùng để tạo. {@code sku}, {@code barcode},
     * {@code description} là {@code null} khi bỏ trống ({@code sku} trống thì sinh khi tạo).
     */
    record NewProduct(String name, UUID categoryId, String unit, String description, long salePrice, String sku,
                      String barcode, int openingQuantity, long openingUnitCost) {
    }

    record Created(ProductView product, boolean replayed) {
    }

    /** Gọi từ controller, NGOÀI transaction ({@link IdempotencyService#execute} tự mở transaction). */
    Created create(UUID idempotencyKey, CreateProductRequest request) {
        NewProduct product = validate(request);
        if (idempotencyKey == null) {
            throw missingKey();
        }
        IdempotentResult<ProductView> result = idempotency.execute(DOCUMENT_TYPE, idempotencyKey,
                idempotency.hash(product), () -> insert(product), this::loadView);
        return new Created(result.value(), result.replayed());
    }

    @Transactional(readOnly = true)
    ProductView get(UUID id) {
        return loadView(id);
    }

    /** Mới nhất trước; {@code page} bắt đầu từ 0, {@code size} mặc định 20, tối đa 100 (AD-19). */
    @Transactional(readOnly = true)
    PageResponse<ProductView> list(Integer page, Integer size) {
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = PageResponse.clampSize(size);
        UUID storeId = context.storeId();
        Long total = jdbc.queryForObject("select count(*) from app.product where store_id = ? and deleted_at is null",
                Long.class, storeId);
        List<ProductView> items = views(PAGE_OF_PRODUCTS, storeId, pageSize, (long) pageNo * pageSize);
        return new PageResponse<>(items, total == null ? 0 : total, pageNo, pageSize);
    }

    /**
     * Ngừng kinh doanh (FR-3, SP-03): Sản phẩm và mọi Biến thể đang kinh doanh (chưa xóa mềm) sang
     * {@code INACTIVE}, trả chi tiết như {@link #get}. Trong CÙNG transaction:
     *
     * <ul>
     *   <li>{@code updated_at}, {@code changed_at} lấy từ {@code Clock}: change-feed báo {@code INACTIVE} để dịch vụ AI
     *       xóa embedding (AD-12).</li>
     *   <li>Phát {@link VariantDeactivated} cho từng Biến thể vừa ngừng, sau khi đã ghi, để {@code inventory} đặt
     *       {@code stock.is_active = false} (AD-14). catalog không đụng bảng {@code stock}.</li>
     *   <li>Ghi Nhật ký {@link AuditAction#CATALOG_PRODUCT_DEACTIVATE}.</li>
     * </ul>
     *
     * <p>Một chiều: Sản phẩm đã ngừng thì trả trạng thái hiện tại, không đổi gì, không phát sự kiện, không ghi nhật ký
     * lần hai. UPDATE có điều kiện {@code status = 'ACTIVE'} nên hai yêu cầu cùng lúc cũng chỉ một yêu cầu đổi được
     * (yêu cầu sau chờ khóa dòng rồi thấy Sản phẩm đã ngừng). Id không có hoặc của Cửa hàng khác: {@code 404}.
     */
    @Transactional
    ProductView deactivate(UUID id) {
        UUID storeId = context.storeId();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int stopped = jdbc.update("""
                update app.product set status = ?, updated_at = ?, changed_at = ?
                where store_id = ? and id = ? and deleted_at is null and status = ?
                """, ProductEntity.INACTIVE, now, now, storeId, id, ProductEntity.ACTIVE);
        if (stopped == 1) {
            List<UUID> variantIds = jdbc.queryForList("""
                    update app.product_variant set status = ?, updated_at = ?
                    where store_id = ? and product_id = ? and deleted_at is null and status = ?
                    returning id
                    """, UUID.class, ProductEntity.INACTIVE, now, storeId, id, ProductEntity.ACTIVE);
            for (UUID variantId : variantIds) {
                events.publishEvent(new VariantDeactivated(storeId, variantId));
            }
            audit.record(AuditAction.CATALOG_PRODUCT_DEACTIVATE, id.toString(),
                    Map.of("status", ProductEntity.ACTIVE), Map.of("status", ProductEntity.INACTIVE));
        }
        return loadView(id);
    }

    /** Chạy trong transaction của {@link IdempotencyService}; trả id Sản phẩm (định danh chứng từ). */
    private UUID insert(NewProduct p) {
        Instant now = clock.instant();
        UUID productId = ids.get();
        UUID variantId = ids.get();
        String sku = p.sku();
        if (sku == null) {
            // 8 ký tự hex có thể trùng SKU đã sinh hoặc đã gõ tay ("SP-…"): đổi id Biến thể tới khi SKU còn trống.
            // Người để trống SKU không được nhận lỗi "SKU đã có".
            sku = generatedSku(variantId);
            for (int attempt = 1; attempt < GENERATED_SKU_ATTEMPTS && skuInUse(sku); attempt++) {
                variantId = ids.get();
                sku = generatedSku(variantId);
            }
        }
        try {
            em.persist(new ProductEntity(productId, p.name(), p.categoryId(), p.unit(), p.description(), now));
            em.persist(ProductVariantEntity.defaultOf(variantId, productId, sku, p.barcode(), p.salePrice(), now));
            em.flush();
        } catch (PersistenceException | DataIntegrityViolationException e) {
            throw translate(e);
        }
        // Sau flush: listener BEFORE_COMMIT của inventory thấy dòng product_variant (khóa ngoại của stock).
        events.publishEvent(new VariantCreated(context.storeId(), variantId, p.openingQuantity(),
                p.openingUnitCost()));
        return productId;
    }

    /** SKU đã có ở một Biến thể chưa xóa mềm của Cửa hàng (đúng phạm vi của {@code product_variant_sku_uq}). */
    private boolean skuInUse(String sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from app.product_variant
                               where store_id = ? and sku = ? and deleted_at is null)
                """, Boolean.class, context.storeId(), sku));
    }

    /** {@code SP-} + 8 ký tự đầu của id Biến thể, viết hoa. */
    static String generatedSku(UUID variantId) {
        return GENERATED_SKU_PREFIX + variantId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private ProductView loadView(UUID id) {
        List<ProductView> found = views(ONE_PRODUCT, context.storeId(), id);
        if (found.isEmpty()) {
            throw ProblemException.notFound("/errors/common/not-found", "Không tìm thấy",
                    "Không tìm thấy Sản phẩm này.");
        }
        return found.getFirst();
    }

    private List<ProductView> views(String productsSql, Object... args) {
        Map<UUID, ViewBuilder> products = new LinkedHashMap<>();
        jdbc.query(VIEW_SQL.formatted(productsSql), (RowCallbackHandler) rs -> {
            UUID id = rs.getObject("id", UUID.class);
            ViewBuilder product = products.get(id);
            if (product == null) {
                product = new ViewBuilder(id, rs.getString("name"), rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"), rs.getString("unit"), rs.getString("description"),
                        rs.getBoolean("has_variants"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(), new ArrayList<>());
                products.put(id, product);
            }
            UUID variantId = rs.getObject("variant_id", UUID.class);
            if (variantId != null) {
                product.variants().add(new ProductView.Variant(variantId, rs.getString("sku"),
                        rs.getString("barcode"), rs.getString("variant_name"), rs.getLong("sale_price"),
                        rs.getBoolean("is_default"), rs.getString("variant_status")));
            }
        }, args);
        return products.values().stream().map(ViewBuilder::build).toList();
    }

    private record ViewBuilder(UUID id, String name, UUID categoryId, String categoryName, String unit,
                               String description, boolean hasVariants, String status, Instant createdAt,
                               Instant updatedAt, List<ProductView.Variant> variants) {

        ProductView build() {
            return new ProductView(id, name, categoryId, categoryName, unit, description, hasVariants, status,
                    createdAt, updatedAt, List.copyOf(variants));
        }
    }

    // --- Kiểm dữ liệu ----------------------------------------------------------------------------------------

    /**
     * Kiểm mọi trường, kể cả {@code categoryId} có thuộc Cửa hàng không, và trả lỗi của tất cả trong một lần
     * ({@code 400 /errors/common/validation}). SKU và mã vạch lưu chữ HOA ({@link Locale#ROOT}), nên
     * {@code abc123} và {@code ABC123} là cùng một mã (unique theo Cửa hàng).
     */
    NewProduct validate(CreateProductRequest request) {
        FieldErrors errors = new FieldErrors();
        String name = TextFields.check(request.name(), "name", NAME, errors);
        String unit = TextFields.check(request.unit(), "unit", UNIT, errors);
        String description = TextFields.check(request.description(), "description", DESCRIPTION, errors);
        // Viết hoa trước khi đếm độ dài: có chữ viết hoa thành hai ký tự (ß → SS)
        String sku = TextFields.check(upperCase(request.sku()), "sku", SKU, errors);
        String barcode = TextFields.check(request.barcode(), "barcode", BARCODE, errors);
        if (barcode != null && !barcode.isEmpty()) {
            if (BARCODE_CHARS.matcher(barcode).matches()) {
                barcode = upperCase(barcode);   // chỉ ASCII nên độ dài không đổi
            } else {
                errors.add("barcode", "Mã vạch chỉ gồm chữ cái không dấu và chữ số");
            }
        }
        if (request.categoryId() != null && !categories.existsById(request.categoryId())) {
            // Danh mục chỉ thấy được trong Cửa hàng hiện tại (@TenantId, RLS): của Cửa hàng khác cũng là "không có"
            errors.add("categoryId", CATEGORY_MISSING);
        }
        long salePrice = wholeNumber(request.salePrice(), INVALID, "salePrice", "Giá bán", MONEY_MAX, errors);
        if (request.salePrice() == null) {
            errors.add("salePrice", "Nhập giá bán");
        }
        long openingQuantity = wholeNumber(request.openingQuantity(), 0, "openingQuantity", "Tồn kho đầu kỳ",
                Integer.MAX_VALUE, errors);
        long openingUnitCost = wholeNumber(request.openingUnitCost(), 0, "openingUnitCost", "Giá vốn ban đầu",
                MONEY_MAX, errors);
        if (openingQuantity > 0 && openingUnitCost == 0) {
            errors.add("openingUnitCost", "Có Tồn kho đầu kỳ thì nhập Giá vốn ban đầu lớn hơn 0");
        } else if (openingQuantity > 0 && openingUnitCost > 0 && BigInteger.valueOf(openingQuantity)
                .multiply(BigInteger.valueOf(openingUnitCost)).compareTo(BigInteger.valueOf(MONEY_MAX)) > 0) {
            // Tổng giá trị tồn đầu kỳ phải còn là một số tiền hợp lệ (inventory nhân hai số này)
            errors.add("openingUnitCost", "Tồn kho đầu kỳ × Giá vốn ban đầu quá lớn");
        }
        errors.throwIfAny();
        return new NewProduct(name, request.categoryId(), unit.isEmpty() ? DEFAULT_UNIT : unit,
                emptyToNull(description), salePrice, emptyToNull(sku), emptyToNull(barcode), (int) openingQuantity,
                openingUnitCost);
    }

    /**
     * Số nguyên từ 0 đến {@code max}. Bỏ trống thì trả {@code whenMissing}; sai thì ghi lỗi và trả {@link #INVALID}.
     * Nhận {@link BigDecimal} để số lẻ như {@code 1.5} báo lỗi thay vì bị cắt im lặng thành 1.
     */
    private static long wholeNumber(BigDecimal raw, long whenMissing, String field, String label, long max,
                                    FieldErrors errors) {
        if (raw == null) {
            return whenMissing;
        }
        if (raw.signum() < 0) {
            errors.add(field, label + " không được âm");
            return INVALID;
        }
        if (raw.stripTrailingZeros().scale() > 0) {
            errors.add(field, label + " phải là số nguyên");
            return INVALID;
        }
        if (raw.compareTo(BigDecimal.valueOf(max)) > 0) {
            errors.add(field, label + " quá lớn");
            return INVALID;
        }
        return raw.longValueExact();
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }

    private static String upperCase(String text) {
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    // --- Lỗi -------------------------------------------------------------------------------------------------

    private static RuntimeException translate(RuntimeException error) {
        if (Constraints.violated(error, BARCODE_UNIQUE)) {
            return ProblemException.conflict("/errors/catalog/barcode-taken", "Mã vạch đã có",
                    "Cửa hàng đã có Sản phẩm dùng mã vạch này. Hãy kiểm tra lại mã vạch.");
        }
        if (Constraints.violated(error, SKU_UNIQUE)) {
            return ProblemException.conflict("/errors/catalog/sku-taken", "SKU đã có",
                    "Cửa hàng đã có Sản phẩm dùng SKU này. Hãy nhập SKU khác, hoặc để trống để hệ thống tự sinh.");
        }
        if (Constraints.violated(error, CATEGORY_FK)) {
            // Danh mục bị xóa ở yêu cầu khác sau khi đã kiểm
            return FieldErrors.of("categoryId", CATEGORY_MISSING);
        }
        return error;
    }

    private static ProblemException missingKey() {
        return ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                "Yêu cầu phải có header Idempotency-Key là một UUID hợp lệ.");
    }
}
