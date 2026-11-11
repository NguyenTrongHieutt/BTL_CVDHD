package vn.nhom17.banhang.catalog.internal.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.catalog.internal.category.CategoryRepository;
import vn.nhom17.banhang.catalog.internal.product.ProductController.CreateProductRequest;
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
                mock(AuditLog.class), next);
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
}
