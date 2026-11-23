package vn.nhom17.banhang.catalog.internal.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.catalog.internal.category.CategoryRepository;
import vn.nhom17.banhang.catalog.internal.product.ProductController.CreateProductRequest;
import vn.nhom17.banhang.catalog.internal.product.ProductController.VariantInput;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;

/**
 * SKU tự sinh ({@code SP-} + 8 ký tự hex) trùng SKU đã có: rất hiếm nên không dựng được bằng id ngẫu nhiên trong test
 * tích hợp; ở đây cho dãy id cố định và giả lập SKU đầu tiên đã có người dùng.
 */
class ProductServiceTest {

    static final UUID STORE = UUID.fromString("d0000000-0000-4000-8000-0000000000aa");
    static final UUID PRODUCT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID TAKEN = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
    static final UUID FREE = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000003");

    final EntityManager em = mock(EntityManager.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final CurrentContext context = mock(CurrentContext.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final IdempotencyService idempotency = mock(IdempotencyService.class);

    @Test
    @SuppressWarnings("unchecked")
    void aGeneratedSkuThatIsAlreadyUsedIsGeneratedAgainWithANewVariantId() {
        ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(PRODUCT, TAKEN, FREE));
        Supplier<UUID> next = ids::removeFirst;
        ProductService service = new ProductService(em, mock(CategoryRepository.class), jdbc, context,
                Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC), events, idempotency,
                mock(AuditLog.class), JsonMapper.builder().build(), next);
        when(context.storeId()).thenReturn(STORE);
        when(jdbc.queryForObject(contains("sku = ?"), eq(Boolean.class), eq(STORE), eq("SP-AAAAAAAA")))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("sku = ?"), eq(Boolean.class), eq(STORE), eq("SP-BBBBBBBB")))
                .thenReturn(false);
        when(idempotency.hash(any())).thenReturn("hash");
        // Chỉ cần phần tạo; phần đọc lại (load) không chạy nên kết quả là null
        when(idempotency.execute(anyString(), any(), anyString(), any(), any())).thenAnswer(call -> {
            ((Supplier<UUID>) call.getArgument(3)).get();
            return new IdempotentResult<>(null, false);
        });

        service.create(UUID.randomUUID(), new CreateProductRequest("Bút bi", null, null, null,
                BigDecimal.valueOf(5000), null, null, null, null));

        ArgumentCaptor<Object> persisted = ArgumentCaptor.forClass(Object.class);
        verify(em, org.mockito.Mockito.times(2)).persist(persisted.capture());
        ProductVariantEntity variant = (ProductVariantEntity) persisted.getAllValues().get(1);
        assertThat(variant.id()).isEqualTo(FREE);
        assertThat(variant.sku()).isEqualTo("SP-BBBBBBBB");
        verify(events).publishEvent(new VariantCreated(STORE, FREE, 0, 0));
        assertThat(ids).isEmpty();
    }

    /**
     * Nhập Excel (SP-06): SKU tự sinh không được lấy SKU mà một dòng chưa ghi của cùng file đã nhập tay
     * ({@code reservedSku}), dù database chưa có SKU đó.
     */
    @Test
    void aGeneratedSkuReservedByAnotherRowOfTheSameFileIsGeneratedAgain() {
        ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(PRODUCT, TAKEN, FREE));
        Supplier<UUID> next = ids::removeFirst;
        ProductService service = new ProductService(em, mock(CategoryRepository.class), jdbc, context,
                Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC), events, idempotency,
                mock(AuditLog.class), JsonMapper.builder().build(), next);
        when(context.storeId()).thenReturn(STORE);
        // Không SKU nào có trong database
        when(jdbc.queryForObject(contains("sku = ?"), eq(Boolean.class), eq(STORE), anyString())).thenReturn(false);

        service.insertInCurrentTransaction(new ProductService.NewProduct("Bút bi", null, "cái", null, 5000, null,
                null, 0, 0), "SP-AAAAAAAA"::equals);

        ArgumentCaptor<Object> persisted = ArgumentCaptor.forClass(Object.class);
        verify(em, org.mockito.Mockito.times(2)).persist(persisted.capture());
        ProductVariantEntity variant = (ProductVariantEntity) persisted.getAllValues().get(1);
        assertThat(variant.id()).isEqualTo(FREE);
        assertThat(variant.sku()).isEqualTo("SP-BBBBBBBB");
        verify(events).publishEvent(new VariantCreated(STORE, FREE, 0, 0));
        assertThat(ids).isEmpty();
    }

    /**
     * Tạo Sản phẩm nhiều Biến thể (SP-07): SKU tự sinh của Biến thể đứng trước không được lấy SKU mà người dùng gõ cho
     * Biến thể đứng sau trong cùng yêu cầu, dù database chưa có SKU đó.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aGeneratedSkuTypedForALaterVariantOfTheSameRequestIsGeneratedAgain() {
        UUID later = UUID.fromString("cccccccc-0000-4000-8000-000000000004");
        ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(PRODUCT, TAKEN, FREE, later));
        Supplier<UUID> next = ids::removeFirst;
        ProductService service = new ProductService(em, mock(CategoryRepository.class), jdbc, context,
                Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC), events, idempotency,
                mock(AuditLog.class), JsonMapper.builder().build(), next);
        when(context.storeId()).thenReturn(STORE);
        // Không SKU nào có trong database
        when(jdbc.queryForObject(contains("sku = ?"), eq(Boolean.class), eq(STORE), anyString())).thenReturn(false);
        when(idempotency.hash(any())).thenReturn("hash");
        when(idempotency.execute(anyString(), any(), anyString(), any(), any())).thenAnswer(call -> {
            ((Supplier<UUID>) call.getArgument(3)).get();
            return new IdempotentResult<>(null, false);
        });
        VariantInput blank = new VariantInput("S", null, null, null, BigDecimal.valueOf(1000), null, null);
        VariantInput typed = new VariantInput("M", null, "sp-aaaaaaaa", null, BigDecimal.valueOf(1000), null, null);

        service.create(UUID.randomUUID(), new CreateProductRequest("Áo", null, null, null, null, null, null, null,
                null, List.of(blank, typed)));

        // Biến thể đầu (mặc định) đổi id tới khi SKU tự sinh khác SKU gõ cho Biến thể sau
        verify(jdbc).update(contains("insert into app.product_variant"), eq(FREE), eq(STORE), eq(PRODUCT),
                eq("SP-BBBBBBBB"), isNull(), eq("S"), anyString(), eq(1000L), eq(true), eq("ACTIVE"), any(), any());
        verify(jdbc).update(contains("insert into app.product_variant"), eq(later), eq(STORE), eq(PRODUCT),
                eq("SP-AAAAAAAA"), isNull(), eq("M"), anyString(), eq(1000L), eq(false), eq("ACTIVE"), any(), any());
        verify(events).publishEvent(new VariantCreated(STORE, FREE, 0, 0));
        verify(events).publishEvent(new VariantCreated(STORE, later, 0, 0));
        assertThat(ids).isEmpty();
    }
}
