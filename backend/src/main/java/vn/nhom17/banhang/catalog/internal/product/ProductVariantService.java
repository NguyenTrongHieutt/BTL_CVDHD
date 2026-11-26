package vn.nhom17.banhang.catalog.internal.product;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.catalog.VariantDeactivated;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.product.ProductVariantController.AddVariantRequest;
import vn.nhom17.banhang.catalog.internal.product.ProductVariantController.UpdateVariantRequest;
import vn.nhom17.banhang.catalog.internal.product.VariantFields.NewVariant;
import vn.nhom17.banhang.catalog.internal.product.VariantFields.VariantEdit;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Thêm, sửa, ngừng kinh doanh từng Biến thể của một Sản phẩm (FR-7, SP-07). Kiểm trường, SKU tự sinh, ghi Biến thể và
 * dịch lỗi ràng buộc dùng chung với {@link ProductService} (SP-01, SP-02).
 *
 * <ul>
 *   <li>Mọi thao tác khóa dòng {@code product} ({@code FOR UPDATE}) trước rồi mới tới {@code product_variant}, cùng
 *       thứ tự với sửa ({@code LOCK_FOR_EDIT}) và ngừng kinh doanh Sản phẩm, nên chạy cùng lúc không deadlock; hai thao
 *       tác Biến thể trên cùng Sản phẩm chạy lần lượt.</li>
 *   <li>Sản phẩm đã ngừng kinh doanh: {@code 409 /errors/catalog/product-inactive}; Sản phẩm, Biến thể không có (hoặc
 *       Biến thể của Sản phẩm khác): {@code 404}.</li>
 *   <li>Thêm, ngừng Biến thể, đổi nhãn hay thuộc tính cập nhật {@code product.changed_at} (change-feed, AD-12):
 *       {@code search_text} gồm nhãn và giá trị thuộc tính của các Biến thể.</li>
 *   <li>Thêm Biến thể phát {@link VariantCreated} (tồn đầu kỳ riêng), ngừng phát {@link VariantDeactivated}, trong
 *       cùng transaction, sau khi đã ghi (AD-14); {@code inventory} nghe {@code BEFORE_COMMIT}.</li>
 * </ul>
 */
@Service
class ProductVariantService {

    /** Loại chứng từ của {@code idempotency_record} khi thêm Biến thể (kèm Tồn kho đầu kỳ {@code OPENING}, AD-7). */
    static final String DOCUMENT_TYPE = "CATALOG_VARIANT";

    private static final TextFields.Rule EXISTING_LABEL =
            TextFields.Rule.optional("Nhãn Biến thể hiện có", VariantFields.LABEL_MAX);
    static final String EXISTING_LABEL_REQUIRED =
            "Sản phẩm đang chỉ có một Biến thể: nhập nhãn cho Biến thể hiện có (ví dụ \"Size S\")";

    private static final String LOCK_PRODUCT = """
            select status, has_variants from app.product
            where store_id = ? and id = ? and deleted_at is null
            for update
            """;
    /** Biến thể chưa xóa mềm của Sản phẩm (đã khóa dòng Sản phẩm), theo thứ tự tạo. */
    private static final String LOCK_VARIANTS = """
            select id, name, attributes::text as attributes, sku, barcode, sale_price, is_default, status
            from app.product_variant
            where store_id = ? and product_id = ? and deleted_at is null
            order by created_at, id
            for update
            """;

    private final ProductService products;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final IdempotencyService idempotency;
    private final AuditLog audit;

    ProductVariantService(ProductService products, JdbcTemplate jdbc, CurrentContext context, Clock clock,
                          ApplicationEventPublisher events, IdempotencyService idempotency, AuditLog audit) {
        this.products = products;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
        this.events = events;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    // --- Thêm ------------------------------------------------------------------------------------------------

    /**
     * Biến thể sắp thêm, đã kiểm; {@code existingVariantName} {@code null} khi bỏ trống. Băm cùng {@code productId}
     * vào {@code request_hash}: cùng khóa, cùng nội dung nhưng khác Sản phẩm là dùng lại khóa.
     */
    record NewAddition(UUID productId, String existingVariantName, NewVariant variant) {
    }

    /**
     * Thêm một Biến thể (gọi từ controller, NGOÀI transaction; {@link IdempotencyService#execute} tự mở transaction).
     * Sản phẩm đang một Biến thể ({@code has_variants = false}) thì body phải có {@code existingVariantName}: Biến thể
     * mặc định hiện có nhận nhãn đó, rồi Sản phẩm thành {@code has_variants = true}. Biến thể mới không phải mặc định.
     *
     * <p>Thứ tự lỗi: dữ liệu sai {@code 400} (mọi trường một lần) → thiếu khóa {@code 422} → {@code 404} →
     * {@code 409 product-inactive} → trùng nhãn, SKU, mã vạch {@code 409}. Gửi lại cùng khóa, cùng nội dung: trả Sản
     * phẩm hiện tại, không thêm lần hai, không phát sự kiện lần hai.
     */
    ProductService.Created add(UUID productId, UUID idempotencyKey, AddVariantRequest request) {
        // has_variants chỉ đổi false → true: đọc trước (không khóa) để báo thiếu nhãn Biến thể hiện có cùng lượt với
        // lỗi dữ liệu khác; kiểm lại sau khi khóa. Sản phẩm không có: không đòi, để 404 báo sau.
        boolean single = !products.hasVariants(productId).orElse(true);
        NewAddition addition = validate(productId, request, single);
        if (idempotencyKey == null) {
            throw ProductService.missingKey();
        }
        IdempotentResult<ProductView> result = idempotency.execute(DOCUMENT_TYPE, idempotencyKey,
                idempotency.hash(addition), () -> insert(addition), this::viewOfVariant);
        return new ProductService.Created(result.value(), result.replayed());
    }

    private NewAddition validate(UUID productId, AddVariantRequest request, boolean single) {
        FieldErrors errors = new FieldErrors();
        String existing = TextFields.check(request.existingVariantName(), "existingVariantName", EXISTING_LABEL,
                errors);
        if (existing != null && existing.isEmpty() && single) {
            errors.add("existingVariantName", EXISTING_LABEL_REQUIRED);
        }
        NewVariant variant = VariantFields.check(request.variant(), "", errors);
        errors.throwIfAny();
        return new NewAddition(productId, existing.isEmpty() ? null : existing, variant);
    }

    /** Chạy trong transaction của {@link IdempotencyService}; trả id Biến thể mới (định danh chứng từ). */
    private UUID insert(NewAddition addition) {
        UUID storeId = context.storeId();
        UUID productId = addition.productId();
        Locked product = lockProduct(storeId, productId);
        OffsetDateTime now = now();
        if (!product.hasVariants()) {
            if (addition.existingVariantName() == null) {
                throw FieldErrors.of("existingVariantName", EXISTING_LABEL_REQUIRED);
            }
            jdbc.update("""
                    update app.product_variant set name = ?, updated_at = ?
                    where store_id = ? and product_id = ? and is_default and deleted_at is null
                    """, addition.existingVariantName(), now, storeId, productId);
        }
        NewVariant v = addition.variant();
        ProductService.VariantCode code = products.allocate(v.sku(), sku -> false);
        products.insertVariant(storeId, productId, code, v.name(), v.attributes(), v.barcode(), v.salePrice(), false,
                now.toInstant());
        jdbc.update("""
                update app.product set has_variants = true, updated_at = ?, changed_at = ?
                where store_id = ? and id = ?
                """, now, now, storeId, productId);
        // Sau khi ghi: listener BEFORE_COMMIT của inventory thấy dòng product_variant (khóa ngoại của stock)
        events.publishEvent(new VariantCreated(storeId, code.variantId(), v.openingQuantity(), v.openingUnitCost()));
        return code.variantId();
    }

    /** Purchase receipt import joins the receipt transaction and uses the same conversion/insert rules. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    UUID insertInCurrentTransaction(NewAddition addition) {
        return insert(addition);
    }

    /** Chi tiết Sản phẩm của Biến thể đã thêm (phát lại theo {@code Idempotency-Key}). */
    private ProductView viewOfVariant(UUID variantId) {
        List<UUID> productIds = jdbc.queryForList("""
                select product_id from app.product_variant where store_id = ? and id = ?
                """, UUID.class, context.storeId(), variantId);
        if (productIds.isEmpty()) {
            throw ProductService.notFound();
        }
        return products.loadView(productIds.getFirst());
    }

    // --- Sửa ------------------------------------------------------------------------------------------------

    /**
     * Sửa nhãn, thuộc tính, SKU, mã vạch, giá bán của một Biến thể, thay toàn bộ ({@link VariantFields#checkEdit}):
     * nhãn trống thì ghép giá trị thuộc tính; SKU trống thì giữ; mã vạch trống thì xóa. Sản phẩm một Biến thể: không
     * có nhãn, thuộc tính (đặt nhãn bằng cách thêm Biến thể). Biến thể đã ngừng kinh doanh vẫn sửa được (không mở bán
     * lại), ví dụ để đổi nhãn, mã cho Biến thể mới dùng.
     *
     * <p>Trong CÙNG transaction: đổi giá thì ghi Nhật ký {@link AuditAction#CATALOG_VARIANT_PRICE_CHANGE} (như SP-02);
     * đổi nhãn hay thuộc tính thì {@code product.changed_at} lấy giờ mới; có thay đổi thì {@code updated_at} của Biến
     * thể và Sản phẩm lấy giờ mới. Y nguyên (sau chuẩn hóa) thì không ghi gì. Dữ liệu sai {@code 400} (báo trước
     * {@code 404}, {@code 409}).
     */
    @Transactional
    ProductView update(UUID productId, UUID variantId, UpdateVariantRequest request) {
        UUID storeId = context.storeId();
        boolean multi = products.hasVariants(productId).orElse(true);
        VariantEdit edit = VariantFields.checkEdit(request, multi);
        Locked product = lockProduct(storeId, productId);
        if (product.hasVariants() != multi) {
            edit = VariantFields.checkEdit(request, product.hasVariants());
        }
        Current current = lockVariants(storeId, productId).stream().filter(v -> v.id().equals(variantId))
                .findFirst().orElseThrow(ProductService::notFound);
        String sku = edit.sku() == null ? current.sku() : edit.sku();
        boolean labelChanged = !Objects.equals(edit.label().name(), current.name())
                || !edit.label().attributes().equals(current.attributes());
        boolean priceChanged = edit.salePrice() != current.salePrice();
        boolean codeChanged = !sku.equals(current.sku()) || !Objects.equals(edit.barcode(), current.barcode());
        if (!labelChanged && !priceChanged && !codeChanged) {
            return products.loadView(productId);
        }
        OffsetDateTime now = now();
        try {
            jdbc.update("""
                    update app.product_variant set name = ?, attributes = ?::jsonb, sku = ?, barcode = ?,
                                                   sale_price = ?, updated_at = ?
                    where store_id = ? and id = ?
                    """, edit.label().name(), products.attributesJson(edit.label().attributes()), sku,
                    edit.barcode(), edit.salePrice(), now, storeId, variantId);
        } catch (DataIntegrityViolationException e) {
            throw ProductService.translateEdit(e);
        }
        if (labelChanged) {
            jdbc.update("update app.product set updated_at = ?, changed_at = ? where store_id = ? and id = ?",
                    now, now, storeId, productId);
        } else {
            jdbc.update("update app.product set updated_at = ? where store_id = ? and id = ?", now, storeId,
                    productId);
        }
        if (priceChanged) {
            audit.record(AuditAction.CATALOG_VARIANT_PRICE_CHANGE, variantId.toString(),
                    Map.of("salePrice", current.salePrice()), Map.of("salePrice", edit.salePrice()));
        }
        return products.loadView(productId);
    }

    // --- Ngừng kinh doanh -------------------------------------------------------------------------------------

    /**
     * Ngừng kinh doanh một Biến thể (một chiều, AD-14): Biến thể sang {@code INACTIVE}; nếu là Biến thể mặc định thì
     * Biến thể đang bán tạo sớm nhất trong số còn lại thành mặc định. Trong CÙNG transaction: {@code product.changed_at},
     * {@code updated_at} lấy giờ mới, phát {@link VariantDeactivated} sau khi đã ghi (inventory đặt
     * {@code stock.is_active = false}), ghi Nhật ký {@link AuditAction#CATALOG_VARIANT_DEACTIVATE}
     * ({@code {status: ACTIVE}} → {@code {status: INACTIVE}}).
     *
     * <p>Biến thể đã ngừng: trả trạng thái hiện tại, không đổi gì, không phát sự kiện, không ghi nhật ký. Biến thể đang
     * bán cuối cùng: {@code 409 /errors/catalog/last-active-variant} (dùng ngừng kinh doanh cả Sản phẩm).
     */
    @Transactional
    ProductView deactivate(UUID productId, UUID variantId) {
        UUID storeId = context.storeId();
        lockProduct(storeId, productId);
        List<Current> variants = lockVariants(storeId, productId);
        Current target = variants.stream().filter(v -> v.id().equals(variantId)).findFirst()
                .orElseThrow(ProductService::notFound);
        if (!ProductEntity.ACTIVE.equals(target.status())) {
            return products.loadView(productId);
        }
        Optional<Current> successor = variants.stream()
                .filter(v -> !v.id().equals(variantId) && ProductEntity.ACTIVE.equals(v.status())).findFirst();
        if (successor.isEmpty()) {
            throw lastActiveVariant();
        }
        OffsetDateTime now = now();
        // Bỏ cờ mặc định trước rồi mới đặt cho Biến thể khác: product_variant_default_uq kiểm ngay từng câu lệnh
        jdbc.update("""
                update app.product_variant set status = ?, is_default = false, updated_at = ?
                where store_id = ? and id = ?
                """, ProductEntity.INACTIVE, now, storeId, variantId);
        if (target.isDefault()) {
            jdbc.update("update app.product_variant set is_default = true, updated_at = ? where store_id = ? and id = ?",
                    now, storeId, successor.get().id());
        }
        jdbc.update("update app.product set updated_at = ?, changed_at = ? where store_id = ? and id = ?",
                now, now, storeId, productId);
        events.publishEvent(new VariantDeactivated(storeId, variantId));
        audit.record(AuditAction.CATALOG_VARIANT_DEACTIVATE, variantId.toString(),
                Map.of("status", ProductEntity.ACTIVE), Map.of("status", ProductEntity.INACTIVE));
        return products.loadView(productId);
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Sản phẩm đã khóa dòng. */
    private record Locked(String status, boolean hasVariants) {
    }

    /** Một Biến thể chưa xóa mềm (đã khóa dòng). */
    private record Current(UUID id, String name, Map<String, String> attributes, String sku, String barcode,
                           long salePrice, boolean isDefault, String status) {
    }

    /** Khóa dòng Sản phẩm; không có: {@code 404}; đã ngừng kinh doanh: {@code 409 product-inactive}. */
    private Locked lockProduct(UUID storeId, UUID productId) {
        List<Locked> found = jdbc.query(LOCK_PRODUCT, (rs, i) -> new Locked(rs.getString("status"),
                rs.getBoolean("has_variants")), storeId, productId);
        if (found.isEmpty()) {
            throw ProductService.notFound();
        }
        if (ProductEntity.INACTIVE.equals(found.getFirst().status())) {
            throw ProductService.productInactive();
        }
        return found.getFirst();
    }

    private List<Current> lockVariants(UUID storeId, UUID productId) {
        return jdbc.query(LOCK_VARIANTS, (rs, i) -> new Current(rs.getObject("id", UUID.class),
                rs.getString("name"), products.parseAttributes(rs.getString("attributes")), rs.getString("sku"),
                rs.getString("barcode"), rs.getLong("sale_price"), rs.getBoolean("is_default"),
                rs.getString("status")), storeId, productId);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    static ProblemException lastActiveVariant() {
        return ProblemException.conflict("/errors/catalog/last-active-variant", "Biến thể đang bán cuối cùng",
                "Đây là Biến thể đang bán cuối cùng của Sản phẩm nên không ngừng riêng được. Muốn thôi bán, hãy ngừng "
                        + "kinh doanh cả Sản phẩm.");
    }
}
