package vn.nhom17.banhang.catalog.internal.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.catalog.internal.category.CategoryRepository;
import vn.nhom17.banhang.catalog.internal.product.ProductImportService.ImportFile;
import vn.nhom17.banhang.catalog.internal.product.ProductImportService.ImportResult;
import vn.nhom17.banhang.catalog.internal.product.ProductImportService.PreviewResult;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;
import vn.nhom17.banhang.platform.ProblemException;

/** Kiểm kế hoạch preview và lưu dùng catalog thật; mô phỏng I/O để chạy cả khi Docker không khả dụng. */
class ProductImportServiceTest {

    static final UUID STORE = UUID.fromString("d0000000-0000-4000-8000-0000000000aa");
    static final String HEADER = "Tên Sản phẩm,Danh mục,Đơn vị tính,Giá bán,Tồn đầu kỳ,Giá vốn ban đầu,SKU,Mã vạch,"
            + "Mô tả,Mã sản phẩm cha,Tên Biến thể,Tên thuộc tính 1,Giá trị thuộc tính 1\n";

    final EntityManager em = mock(EntityManager.class);
    final CategoryRepository categories = mock(CategoryRepository.class);
    final org.springframework.context.ApplicationEventPublisher events = mock(
            org.springframework.context.ApplicationEventPublisher.class);
    final CurrentContext context = mock(CurrentContext.class);
    final IdempotencyService idempotency = mock(IdempotencyService.class);
    final JsonMapper json = JsonMapper.builder().build();
    final Set<String> takenSkus = new HashSet<>();
    final Set<String> takenBarcodes = new HashSet<>();
    final Map<UUID, String> results = new HashMap<>();
    final Map<UUID, UUID> documents = new HashMap<>();
    final Map<UUID, String> hashes = new HashMap<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
        Object[] args = invocation.getArguments();
        if (invocation.getMethod().getName().equals("update") && args[0] instanceof String sql
                && sql.contains("insert into app.product_import")) {
            results.put((UUID) args[1], (String) args[5]);
            return 1;
        }
        if (invocation.getMethod().getName().equals("queryForObject") && args[0] instanceof String sql
                && sql.contains("select result::text")) {
            return results.get((UUID) args[3]);
        }
        return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    final ProductService products = new ProductService(em, categories, jdbc, context, clock, events, idempotency,
            mock(AuditLog.class), json);
    final ProductImportService imports = new ProductImportService(products, categories, em, jdbc, context, clock,
            idempotency, json);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(context.storeId()).thenReturn(STORE);
        when(context.userIdIfPresent()).thenReturn(Optional.empty());
        doAnswer(call -> {
            String sql = call.getArgument(0);
            RowCallbackHandler callback = call.getArgument(2);
            for (String code : sql.contains("select sku ") ? takenSkus : takenBarcodes) {
                ResultSet row = mock(ResultSet.class);
                when(row.getString(1)).thenReturn(code);
                callback.processRow(row);
            }
            return null;
        }).when(jdbc).query(contains("from app.product_variant"), any(PreparedStatementSetter.class),
                any(RowCallbackHandler.class));
        when(idempotency.execute(anyString(), any(), anyString(), any(), any())).thenAnswer(call -> {
            UUID key = call.getArgument(1);
            String hash = call.getArgument(2);
            Supplier<UUID> create = call.getArgument(3);
            Function<UUID, ImportResult> load = call.getArgument(4);
            if (documents.containsKey(key)) {
                assertThat(hash).isEqualTo(hashes.get(key));
                return new IdempotentResult<>(load.apply(documents.get(key)), true);
            }
            UUID id = create.get();
            documents.put(key, id);
            hashes.put(key, hash);
            return new IdempotentResult<>(load.apply(id), false);
        });
    }

    @Test
    void previewGroupsNonAdjacentNormalizedParentCodesAndNeverWrites() {
        ImportFile file = csv(HEADER
                + "Áo thun,Thời trang,chiếc,10000,2,7000,ao-s,000123,Mềm, ao   thun ,,Cỡ,S\n"
                + "Bút,,,2000,,,but,,,,,,\n"
                + ",,,12000,0,,ao-m,000124,,AO THUN,,Cỡ,M\n");

        PreviewResult preview = imports.preview(file);

        assertThat(preview.totalRows()).isEqualTo(3);
        assertThat(preview.validProducts()).isEqualTo(2);
        assertThat(preview.validRows()).isEqualTo(3);
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.rows()).extracting(r -> r.row()).containsExactly(2, 3, 4);
        assertThat(preview.rows().getFirst().parentCode()).isEqualTo("AO THUN");
        assertThat(preview.rows().getFirst().variantName()).isEqualTo("S");
        assertThat(preview.rows().getFirst().attributes()).containsExactly(Map.entry("Cỡ", "S"));
        assertThat(preview.rows().getFirst().sku()).isEqualTo("AO-S");
        assertThat(preview.rows().getFirst().barcode()).isEqualTo("000123");
        assertThat(preview.rows().get(2).productName()).isEqualTo("Áo thun");
        assertThat(preview.rows().get(2).categoryName()).isEqualTo("Thời trang");
        assertThat(imports.preview(file).previewToken()).isEqualTo(preview.previewToken()).hasSize(64);
        assertNoWrites();
        verifyNoInteractions(categories, em, events);
        assertThat(documents).isEmpty();
    }

    @Test
    void anInvalidVariantRejectsItsWholeGroupAndDoesNotReserveItsCodes() {
        PreviewResult preview = imports.preview(csv(HEADER
                + "Áo,,,1000,,,REUSABLE,,,AO,S,,\n"
                + ",,,sai,,,AO-M,,,ao,M,,\n"
                + "Bút,,,2000,,,reusable,,,,,,\n"));

        assertThat(preview.validProducts()).isEqualTo(1);
        assertThat(preview.validRows()).isEqualTo(1);
        assertThat(preview.rows()).extracting(r -> r.valid()).containsExactly(false, false, true);
        assertThat(preview.errors()).anySatisfy(error -> {
            assertThat(error.row()).isEqualTo(3);
            assertThat(error.column()).isEqualTo("Giá bán");
            assertThat(error.message()).contains("phải là số");
        }).anySatisfy(error -> assertThat(error.message()).contains("không nhập toàn bộ nhóm"));
        assertNoWrites();
    }

    @Test
    void inconsistentCommonFieldsRepeatedAttributesAndSingleVariantGroupsAreRejected() {
        PreviewResult preview = imports.preview(csv(HEADER
                + "Áo,,,1000,,,A,,,AO,S,Cỡ,S\n"
                + "Tên khác,,,1000,,,B,,,AO,M,Cỡ,M\n"
                + "Một mình,,,1000,,,C,,,LE,L,,\n"));
        assertThat(preview.validProducts()).isZero();
        assertThat(preview.errors()).anySatisfy(e -> assertThat(e.message()).contains("không nhất quán"))
                .anySatisfy(e -> assertThat(e.message()).contains("từ 2 đến 50"));

        String headers = "Tên Sản phẩm,Giá bán,Mã sản phẩm cha,Tên Biến thể,Tên thuộc tính 1,Giá trị thuộc tính 1,"
                + "Tên thuộc tính 2,Giá trị thuộc tính 2\n";
        PreviewResult duplicate = imports.preview(csv(headers
                + "Áo,1000,AO,S,Cỡ,S,cỡ,M\n,1000,AO,M,Cỡ,M,,\n"));
        assertThat(duplicate.validProducts()).isZero();
        assertThat(duplicate.errors()).anySatisfy(e -> {
            assertThat(e.column()).isEqualTo("Tên thuộc tính 2");
            assertThat(e.message()).contains("bị lặp");
        });
    }

    @Test
    void duplicateVariantLabelsAndCodesInvalidateTheEntireGroup() {
        PreviewResult labels = imports.preview(csv(HEADER
                + "Áo,,,1000,,,A,,,AO,S,,\n,,,1000,,,B,,,AO,s,,\n"));
        assertThat(labels.validRows()).isZero();
        assertThat(labels.errors()).anySatisfy(e -> assertThat(e.column()).isEqualTo("Tên Biến thể"));
        PreviewResult codes = imports.preview(csv(HEADER
                + "Áo,,,1000,,,A,,,AO,S,,\n,,,1000,,,a,,,AO,M,,\n"));
        assertThat(codes.validRows()).isZero();
        assertThat(codes.errors()).anySatisfy(e -> assertThat(e.message()).contains("SKU A trùng với dòng 2"));
    }

    @Test
    void aRepeatedCommonFieldCannotHideInvalidControlCharacters() {
        String headers = "Tên Sản phẩm,Danh mục,Giá bán,Mã sản phẩm cha,Tên Biến thể\n";
        PreviewResult preview = imports.preview(csv(headers
                + "Áo,Thời trang,1000,AO,S\n,\"Thời\ntrang\",1000,AO,M\n"));
        assertThat(preview.validProducts()).isZero();
        assertThat(preview.errors()).anySatisfy(e -> {
            assertThat(e.column()).isEqualTo("Danh mục");
            assertThat(e.message()).contains("không nhất quán");
        });
    }

    @Test
    void groupSizeUsesTheCatalogFiftyVariantLimit() {
        String headers = "Tên Sản phẩm,Giá bán,Mã sản phẩm cha,Tên Biến thể\n";
        StringBuilder body = new StringBuilder(headers);
        for (int i = 1; i <= 50; i++) {
            body.append("Áo,1000,AO,Cỡ ").append(i).append('\n');
        }
        PreviewResult fifty = imports.preview(csv(body.toString()));
        assertThat(fifty.validProducts()).isEqualTo(1);
        assertThat(fifty.validRows()).isEqualTo(50);
        body.append("Áo,1000,AO,Cỡ 51\n");
        PreviewResult tooMany = imports.preview(csv(body.toString()));
        assertThat(tooMany.validProducts()).isZero();
        assertThat(tooMany.validRows()).isZero();
        assertThat(tooMany.errors()).anySatisfy(e -> assertThat(e.message()).contains("từ 2 đến 50"));
    }

    @Test
    void attributeErrorsPointToTheActualPairAndNameOrValueColumn() {
        String headers = "Tên Sản phẩm,Giá bán,Mã sản phẩm cha,Tên Biến thể,Tên thuộc tính 1,Giá trị thuộc tính 1,"
                + "Tên thuộc tính 2,Giá trị thuộc tính 2\n";
        PreviewResult preview = imports.preview(csv(headers
                + "Áo,1000,AO,S,Cỡ,S,Màu,\n,1000,AO,M,Cỡ,M,,Đỏ\n"));
        assertThat(preview.validProducts()).isZero();
        assertThat(preview.errors()).anySatisfy(e -> {
            assertThat(e.row()).isEqualTo(2);
            assertThat(e.column()).isEqualTo("Giá trị thuộc tính 2");
            assertThat(e.message()).contains("Nhập giá trị");
        }).anySatisfy(e -> {
            assertThat(e.row()).isEqualTo(3);
            assertThat(e.column()).isEqualTo("Tên thuộc tính 2");
            assertThat(e.message()).contains("Nhập tên");
        });
    }

    @Test
    void saveUsesTheValidatedMultiVariantPathAndPublishesOpeningStockForEachVariant() {
        ImportFile file = csv(HEADER
                + "Áo,,,10000,2,7000,A,,,AO,S,,\n"
                + ",,,12000,3,8000,B,,,AO,M,,\n"
                + "Bút,,,2000,,,BUT,,,,,,\n");
        String token = imports.preview(file).previewToken();

        var saved = imports.importFile(UUID.randomUUID(), file, token);

        assertThat(saved.result().imported()).isEqualTo(2);
        assertThat(saved.result().failed()).isEmpty();
        assertThat(saved.result().createdCategories()).isEmpty();
        List<VariantCreated> created = mockingDetails(events).getInvocations().stream()
                .map(i -> i.getArgument(0)).filter(VariantCreated.class::isInstance).map(VariantCreated.class::cast)
                .toList();
        assertThat(created).extracting(e -> e.openingQuantity() + "x" + e.openingUnitCost())
                .containsExactly("2x7000", "3x8000", "0x0");
        assertThat(mockingDetails(em).getInvocations().stream().filter(i -> i.getMethod().getName().equals("persist"))
                .map(i -> i.getArgument(0)).filter(ProductEntity.class::isInstance)).hasSize(2);
        assertThat(results).hasSize(1);
    }

    @Test
    void changedValidationAfterPreviewReturnsStaleBeforeAnyWrites() {
        ImportFile file = csv(HEADER
                + "Áo,Không được tạo,,1000,,,A,,,AO,S,,\n,,,1000,,,B,,,AO,M,,\n");
        String token = imports.preview(file).previewToken();
        takenSkus.add("B");

        assertThatThrownBy(() -> imports.importFile(UUID.randomUUID(), file, token))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status().value()).isEqualTo(409);
                    assertThat(problem.type()).isEqualTo("/errors/catalog/import-preview-stale");
                });
        assertNoWrites();
        verifyNoInteractions(categories, em, events);
        assertThat(imports.preview(file).previewToken()).isNotEqualTo(token);
    }

    @Test
    void successfulSameKeyReplayKeepsItsResultAfterTheCatalogChanges() {
        ImportFile file = csv(HEADER + "Bút,,,2000,,,BUT,,,,,,\n");
        String token = imports.preview(file).previewToken();
        UUID key = UUID.randomUUID();
        var first = imports.importFile(key, file, token);
        long writes = writeCount();
        takenSkus.add("BUT");

        var replay = imports.importFile(key, file, token);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(writeCount()).isEqualTo(writes);
    }

    @Test
    void legacyXlsxWithoutPreviewStillSkipsTheOriginalExample() {
        ImportFile file = imports.read(new MockMultipartFile("file", "mau.xlsx", ProductImportTemplate.CONTENT_TYPE,
                ProductImportTemplate.build()));
        ImportResult result = imports.importFile(UUID.randomUUID(), file).result();
        assertThat(result.imported()).isZero();
        assertThat(result.failed()).singleElement().satisfies(e -> {
            assertThat(e.row()).isEqualTo(2);
            assertThat(e.column()).isNull();
            assertThat(e.message()).isEqualTo(ProductImportTemplate.EXAMPLE_ROW_MESSAGE);
        });
    }

    private ImportFile csv(String content) {
        return imports.read(new MockMultipartFile("file", "san-pham.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8)));
    }

    private long writeCount() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("update")).count();
    }

    private void assertNoWrites() {
        assertThat(writeCount()).isZero();
        assertThat(results).isEmpty();
    }
}
