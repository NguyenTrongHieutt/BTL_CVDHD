package vn.nhom17.banhang.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.platform.TenantScope;

/** KHO-03: nhập một phiếu từ file hỗn hợp, dùng catalog và tồn kho thật trên PostgreSQL có RLS. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class PurchaseReceiptImportIntegrationTest {
    private static final String PRODUCTS = "/api/v1/catalog/products";
    private static final String RECEIPTS = "/api/v1/inventory/purchase-receipts";
    private static final String IMPORT = RECEIPTS + "/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> HEADERS = List.of("Mã sản phẩm cha", "Tên Sản phẩm", "Tên Biến thể", "SKU",
            "Mã vạch", "Danh mục", "Đơn vị tính", "Mô tả", "Giá bán", "Số lượng", "Giá nhập",
            "Tên thuộc tính 1", "Giá trị thuộc tính 1");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    @Autowired ApplicationEvents events;
    @Autowired PostgreSQLContainer postgres;

    private record Owner(Cookie cookie, UUID storeId, UUID supplierId) {}
    private record Product(UUID id, UUID variantId) {}

    @Test
    void mixedCsvCreatesGroupedProductsAndReceivesAllRowsOnlyOnceOnOneReceipt() throws Exception {
        Owner owner = newStore();
        Product old = createProduct(owner, Map.of("name", "Hàng đang bán", "sku", "OLD-001", "salePrice", 200,
                "openingQuantity", 2, "openingUnitCost", 100));
        byte[] file = csv(
                List.of("", "Tên không được ghi đè", "", "old-001", "BARCODE-FAKE", "Danh mục không được tạo", "túi",
                        "Mô tả không được ghi", "không phải số", "3", "120", "", ""),
                List.of("AO-THUN", "Áo thun", "", "AO-S", "000123", "Thời trang", "chiếc", "Vải mềm",
                        "100000", "2", "70000", "Cỡ", "S"),
                List.of("ao-thun", "", "", "AO-M", "000124", "", "", "", "110000", "3", "72000", "Cỡ", "M"),
                List.of("", "Bút bi", "", "BUT-01", "000125", "Văn phòng phẩm", "cây", "", "5000", "4", "3000", "", ""));

        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[0].action").value("EXISTING"))
                .andExpect(jsonPath("$.rows[0].sku").value("OLD-001"))
                .andExpect(jsonPath("$.rows[0].productName").value("Hàng đang bán"))
                .andExpect(jsonPath("$.rows[0].values.salePrice").value("200"))
                .andExpect(jsonPath("$.rows[0].values.unit").value("cái"))
                .andExpect(jsonPath("$.rows[0].values.barcode").isEmpty())
                .andExpect(jsonPath("$.rows[1].action").value("NEW_PRODUCT"))
                .andExpect(jsonPath("$.rows[2].productName").value("Áo thun"))
                .andExpect(jsonPath("$.rows[2].variantName").value("M"))
                .andExpect(jsonPath("$.existingRows").value(1))
                .andExpect(jsonPath("$.newProducts").value(2))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.totalAmount").value(368360))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        assertThat(count(owner, "app.product")).isEqualTo(1);
        assertThat(count(owner, "app.product_variant")).isEqualTo(1);
        assertThat(count(owner, "app.category")).isZero();
        assertThat(count(owner, "app.purchase_receipt")).isZero();
        assertThat(count(owner, "app.stock_ledger")).isEqualTo(1);
        assertStock(owner, "OLD-001", 2, 100);

        UUID key = UUID.randomUUID();
        String token = preview.get("previewToken").asString();
        JsonNode receipt = submit(owner, csvFile(file), null, key, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierId").value(owner.supplierId().toString()))
                .andExpect(jsonPath("$.totalAmount").value(368360))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        UUID receiptId = UUID.fromString(receipt.get("receiptId").asString());
        submit(owner, csvFile(file), null, key, token)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.receiptId").value(receiptId.toString()));

        assertThat(count(owner, "app.purchase_receipt")).isEqualTo(1);
        assertThat(count(owner, "app.purchase_receipt_line")).isEqualTo(4);
        assertThat(count(owner, "app.product")).isEqualTo(3);
        assertThat(count(owner, "app.product_variant")).isEqualTo(4);
        assertStock(owner, "OLD-001", 5, 112);
        assertStock(owner, "AO-S", 2, 70000);
        assertStock(owner, "AO-M", 3, 72000);
        assertStock(owner, "BUT-01", 4, 3000);
        asStore(owner, () -> {
            assertThat(jdbc.queryForMap("select name, unit, description from app.product where id = ?", old.id()))
                    .containsEntry("name", "Hàng đang bán").containsEntry("unit", "cái").containsEntry("description", null);
            assertThat(jdbc.queryForMap("select sale_price, barcode from app.product_variant where id = ?", old.variantId()))
                    .containsEntry("sale_price", 200L).containsEntry("barcode", null);
            assertThat(jdbc.queryForObject("""
                    select count(distinct product_id) from app.product_variant where sku in ('AO-S', 'AO-M')
                    """, Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    select has_variants from app.product where id =
                        (select product_id from app.product_variant where sku = 'AO-S')
                    """, Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("""
                    select count(*) from app.stock_ledger where document_type = 'PURCHASE_RECEIPT' and document_id = ?
                    """, Long.class, receiptId)).isEqualTo(4);
            assertThat(jdbc.queryForObject("""
                    select count(*) from app.stock_ledger where variant_id <> ? and document_type <> 'PURCHASE_RECEIPT'
                    """, Long.class, old.variantId())).isZero();
            assertThat(jdbc.queryForList("""
                    select v.sku || ':' || l.quantity || ':' || l.unit_cost
                    from app.purchase_receipt_line l join app.product_variant v on v.id = l.variant_id
                    where l.receipt_id = ? order by l.line_no
                    """, String.class, receiptId)).containsExactly("OLD-001:3:120", "AO-S:2:70000", "AO-M:3:72000", "BUT-01:4:3000");
            return null;
        });
        assertThat(events.stream(VariantCreated.class)
                .filter(event -> !event.variantId().equals(old.variantId())).toList())
                .hasSize(3).allSatisfy(event -> {
                    assertThat(event.openingQuantity()).isZero();
                    assertThat(event.openingUnitCost()).isZero();
                });
    }

    @Test
    void anExistingSkuAnchorsItsGroupSoANewVariantBelongsToTheSameProduct() throws Exception {
        Owner owner = newStore();
        Product old = createProduct(owner, Map.of("name", "Áo polo", "variants", List.of(
                Map.of("sku", "POLO-RED", "name", "Đỏ", "salePrice", 100000, "attributes", Map.of("Màu", "Đỏ")),
                Map.of("sku", "POLO-BLUE", "name", "Xanh", "salePrice", 100000, "attributes", Map.of("Màu", "Xanh")))));
        byte[] file = csv(
                List.of("POLO", "", "", "POLO-RED", "", "", "", "", "", "1", "60000", "", ""),
                List.of("polo", "", "Lục", "POLO-GREEN", "001111", "", "", "", "120000", "2", "65000", "Màu", "Lục"));

        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existingRows").value(1))
                .andExpect(jsonPath("$.newProducts").value(0))
                .andExpect(jsonPath("$.newVariants").value(1))
                .andExpect(jsonPath("$.rows[1].action").value("NEW_VARIANT"))
                .andExpect(jsonPath("$.rows[1].productName").value("Áo polo"))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        submit(owner, csvFile(file), null, UUID.randomUUID(), preview.get("previewToken").asString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(190000));

        assertThat(count(owner, "app.product")).isEqualTo(1);
        assertThat(count(owner, "app.product_variant")).isEqualTo(3);
        assertThat(asStore(owner, () -> jdbc.queryForObject(
                "select product_id from app.product_variant where sku = 'POLO-GREEN'", UUID.class))).isEqualTo(old.id());
        assertStock(owner, "POLO-RED", 1, 60000);
        assertStock(owner, "POLO-BLUE", 0, 0);
        assertStock(owner, "POLO-GREEN", 2, 65000);
        assertThat(asStore(owner, () -> jdbc.queryForObject(
                "select count(*) from app.stock_ledger where document_type = 'OPENING'", Long.class))).isZero();
    }

    @Test
    void convertingASingleVariantProductRequiresNamingItsCurrentVariantBeforeImportingTheNewOne() throws Exception {
        Owner owner = newStore();
        Product old = createProduct(owner, Map.of("name", "Áo polo đơn", "sku", "POLO-BASE", "salePrice", 100000,
                "openingQuantity", 1, "openingUnitCost", 60000));
        byte[] file = csv(
                List.of("POLO", "", "", "POLO-BASE", "", "", "", "", "", "2", "65000", "", ""),
                List.of("POLO", "", "Xanh", "POLO-NEW", "", "", "", "", "120000", "3", "70000", "Màu", "Xanh"));
        JsonNode invalid = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        assertThat(invalid.get("errors")).anySatisfy(error -> {
            assertThat(error.get("row").asInt()).isEqualTo(2);
            assertThat(error.get("column").asString()).isEqualTo("Tên Biến thể");
        });
        submit(owner, csvFile(file), null, UUID.randomUUID(), invalid.get("previewToken").asString())
                .andExpect(status().isBadRequest());
        assertThat(count(owner, "app.product_variant")).isEqualTo(1);
        assertStock(owner, "POLO-BASE", 1, 60000);

        String edits = """
                [{"row":2,"values":{"variantName":"Đỏ"}}]
                """;
        JsonNode corrected = preview(owner, csvFile(file), edits)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newProducts").value(0))
                .andExpect(jsonPath("$.newVariants").value(1))
                .andExpect(jsonPath("$.rows[0].variantName").value("Đỏ"))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        submit(owner, csvFile(file), edits, UUID.randomUUID(), corrected.get("previewToken").asString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(340000));
        assertThat(count(owner, "app.product")).isEqualTo(1);
        assertThat(count(owner, "app.product_variant")).isEqualTo(2);
        asStore(owner, () -> {
            assertThat(jdbc.queryForObject("select has_variants from app.product where id = ?", Boolean.class, old.id()))
                    .isTrue();
            assertThat(jdbc.queryForObject("select name from app.product_variant where id = ?", String.class, old.variantId()))
                    .isEqualTo("Đỏ");
            assertThat(jdbc.queryForObject("select product_id from app.product_variant where sku = 'POLO-NEW'", UUID.class))
                    .isEqualTo(old.id());
            return null;
        });
        assertStock(owner, "POLO-BASE", 3, 63333);
        assertStock(owner, "POLO-NEW", 3, 70000);
    }

    @Test
    void changingTheCatalogPriceRequiresANewPreviewBeforeAnyImportWrites() throws Exception {
        Owner owner = newStore();
        Product old = createProduct(owner, Map.of("name", "Hàng vừa đổi giá", "sku", "PRICED", "salePrice", 10000,
                "openingQuantity", 2, "openingUnitCost", 7000));
        byte[] file = csv(
                List.of("", "", "", "PRICED", "", "", "", "", "", "1", "8000", "", ""),
                List.of("", "Hàng chưa được tạo", "", "AFTER-PRICE", "", "", "", "", "10000", "1", "7000", "", ""));
        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        mvc.perform(put(PRODUCTS + "/{id}", old.id()).cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hàng vừa đổi giá\",\"sku\":\"PRICED\",\"salePrice\":11000}"))
                .andExpect(status().isOk());
        submit(owner, csvFile(file), null, UUID.randomUUID(), preview.get("previewToken").asString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/inventory/receipt-import-preview-stale"));
        assertThat(count(owner, "app.product")).isEqualTo(1);
        assertThat(count(owner, "app.purchase_receipt")).isZero();
        assertStock(owner, "PRICED", 2, 7000);

        JsonNode refreshed = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        submit(owner, csvFile(file), null, UUID.randomUUID(), refreshed.get("previewToken").asString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(15000));
        assertStock(owner, "PRICED", 3, 7333);
        assertStock(owner, "AFTER-PRICE", 1, 7000);
        assertThat(asStore(owner, () -> jdbc.queryForObject(
                "select sale_price from app.product_variant where id = ?", Long.class, old.variantId()))).isEqualTo(11000);
    }

    @Test
    void duplicateVariantNamesInANewGroupBlockEveryCatalogAndReceiptWrite() throws Exception {
        Owner owner = newStore();
        byte[] file = csv(
                List.of("AO", "Áo mới", "Mẫu", "DUP-A", "", "", "", "", "10000", "1", "7000", "", ""),
                List.of("AO", "", " mẫu ", "DUP-B", "", "", "", "", "10000", "1", "7000", "", ""));
        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        assertThat(preview.get("errors")).anySatisfy(error -> {
            assertThat(error.get("row").asInt()).isEqualTo(3);
            assertThat(error.get("column").asString()).isEqualTo("Tên Biến thể");
        });
        submit(owner, csvFile(file), null, UUID.randomUUID(), preview.get("previewToken").asString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/inventory/receipt-import-invalid"));
        assertNothingImported(owner);
    }

    @Test
    void aGroupCannotMergeTwoExistingProducts() throws Exception {
        Owner owner = newStore();
        createProduct(owner, Map.of("name", "Sản phẩm thứ nhất", "sku", "FIRST", "salePrice", 10000));
        createProduct(owner, Map.of("name", "Sản phẩm thứ hai", "sku", "SECOND", "salePrice", 10000));
        byte[] file = csv(
                List.of("SAME", "", "Một", "FIRST", "", "", "", "", "", "1", "7000", "", ""),
                List.of("SAME", "", "Hai", "SECOND", "", "", "", "", "", "1", "7000", "", ""),
                List.of("SAME", "", "Ba", "THIRD", "", "", "", "", "10000", "1", "7000", "", ""));
        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        assertThat(preview.get("errors")).isNotEmpty();
        submit(owner, csvFile(file), null, UUID.randomUUID(), preview.get("previewToken").asString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/inventory/receipt-import-invalid"));
        assertThat(count(owner, "app.product")).isEqualTo(2);
        assertThat(count(owner, "app.product_variant")).isEqualTo(2);
        assertThat(count(owner, "app.purchase_receipt")).isZero();
        assertThat(count(owner, "app.stock_ledger")).isZero();
        assertStock(owner, "FIRST", 0, 0);
        assertStock(owner, "SECOND", 0, 0);
    }

    @Test
    void matchingSkuInAnotherStoreIsCreatedLocallyInsteadOfReceivingTheOtherStoresStock() throws Exception {
        Owner other = newStore();
        createProduct(other, Map.of("name", "Hàng cửa hàng khác", "sku", "SHARED-SKU", "salePrice", 15000,
                "openingQuantity", 3, "openingUnitCost", 9000));
        Owner owner = newStore();
        byte[] file = csv(List.of("", "Hàng cửa hàng hiện tại", "", "SHARED-SKU", "", "", "", "",
                "12000", "2", "8000", "", ""));
        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].action").value("NEW_PRODUCT"))
                .andExpect(jsonPath("$.existingRows").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        submit(owner, csvFile(file), null, UUID.randomUUID(), preview.get("previewToken").asString())
                .andExpect(status().isCreated());
        assertStock(owner, "SHARED-SKU", 2, 8000);
        assertStock(other, "SHARED-SKU", 3, 9000);
        assertThat(count(owner, "app.purchase_receipt")).isEqualTo(1);
        assertThat(count(other, "app.purchase_receipt")).isZero();
    }

    @Test
    void invalidRowsBlockTheWholeReceiptUntilEditsAreRevalidatedWithANewPreview() throws Exception {
        Owner owner = newStore();
        byte[] file = csv(
                List.of("", "Hàng đúng", "", "VALID", "", "", "", "", "10000", "2", "7000", "", ""),
                List.of("", "Hàng cần sửa", "", "FIX-ME", "", "", "", "", "12000", "nhiều", "8000", "", ""));
        JsonNode invalid = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].valid").value(true))
                .andExpect(jsonPath("$.rows[1].valid").value(false))
                .andExpect(jsonPath("$.rows[1].values.quantity").value("nhiều"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        assertThat(invalid.get("errors")).anySatisfy(error -> {
            assertThat(error.get("row").asInt()).isEqualTo(3);
            assertThat(error.get("column").asString()).isEqualTo("Số lượng");
            assertThat(error.get("message").asString()).isNotBlank();
        });
        submit(owner, csvFile(file), null, UUID.randomUUID(), invalid.get("previewToken").asString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/inventory/receipt-import-invalid"));
        assertNothingImported(owner);

        String edits = """
                [{"row":3,"values":{"quantity":"3"}}]
                """;
        JsonNode corrected = preview(owner, csvFile(file), edits)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[1].quantity").value(3))
                .andExpect(jsonPath("$.rows[1].valid").value(true))
                .andExpect(jsonPath("$.rows[1].values.quantity").value("3"))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.totalAmount").value(38000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        assertThat(corrected.get("previewToken").asString()).isNotEqualTo(invalid.get("previewToken").asString());
        submit(owner, csvFile(file), edits, UUID.randomUUID(), invalid.get("previewToken").asString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/inventory/receipt-import-preview-stale"));
        assertNothingImported(owner);
        submit(owner, csvFile(file), edits, UUID.randomUUID(), corrected.get("previewToken").asString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(38000));
        assertThat(count(owner, "app.purchase_receipt")).isEqualTo(1);
        assertThat(count(owner, "app.purchase_receipt_line")).isEqualTo(2);
        assertStock(owner, "VALID", 2, 7000);
        assertStock(owner, "FIX-ME", 3, 8000);
    }

    @Test
    void stockOverflowPreventsCreatingAnyPartOfTheImport() throws Exception {
        Owner owner = newStore();
        createProduct(owner, Map.of("name", "Kho đầy", "sku", "FULL", "salePrice", 10,
                "openingQuantity", Integer.MAX_VALUE, "openingUnitCost", 1));
        byte[] file = csv(
                List.of("", "Hàng không được tạo dở", "", "ROLLBACK", "", "Danh mục không được tạo dở", "", "",
                        "100", "2", "50", "", ""),
                List.of("", "", "", "FULL", "", "", "", "", "", "1", "1", "", ""));
        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        submit(owner, csvFile(file), null, UUID.randomUUID(), preview.get("previewToken").asString())
                .andExpect(status().is4xxClientError());

        assertThat(count(owner, "app.product")).isEqualTo(1);
        assertThat(count(owner, "app.product_variant")).isEqualTo(1);
        assertThat(count(owner, "app.category")).isZero();
        assertThat(count(owner, "app.stock")).isEqualTo(1);
        assertThat(count(owner, "app.stock_ledger")).isEqualTo(1);
        assertThat(count(owner, "app.purchase_receipt")).isZero();
        assertThat(count(owner, "app.purchase_receipt_line")).isZero();
        assertStock(owner, "FULL", Integer.MAX_VALUE, 1);
    }

    @Test
    void aLateDatabaseFailureRollsBackCatalogStockReceiptAndTheIdempotencyKey() throws Exception {
        Owner owner = newStore();
        byte[] file = csv(List.of("", "Hàng phải tạo nguyên tử", "", "ATOMIC", "", "Danh mục phải tạo nguyên tử",
                "", "", "10000", "2", "7000", "", ""));
        JsonNode preview = preview(owner, csvFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        UUID key = UUID.randomUUID();
        String token = preview.get("previewToken").asString();
        String trigger = "test_receipt_failure_" + owner.storeId().toString().replace("-", "");

        // Chỉ database Testcontainers và Cửa hàng của test này: gây lỗi ở lần ghi cuối, sau catalog và tồn kho thật.
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), "app_owner",
                TestcontainersConfiguration.OWNER_PASSWORD); Statement ddl = connection.createStatement()) {
            ddl.execute("""
                    create function app.%s() returns trigger language plpgsql as $$
                    begin raise exception 'forced receipt line failure'; end;
                    $$
                    """.formatted(trigger));
            try {
                ddl.execute("""
                        create trigger %s before insert on app.purchase_receipt_line
                        for each row when (new.store_id = '%s'::uuid) execute function app.%s()
                        """.formatted(trigger, owner.storeId(), trigger));
                var failure = submit(owner, csvFile(file), null, key, token)
                        .andExpect(status().is5xxServerError()).andReturn().getResolvedException();
                assertThat(failure).hasStackTraceContaining("forced receipt line failure");
                assertNothingImported(owner);
            } finally {
                ddl.execute("drop trigger if exists " + trigger + " on app.purchase_receipt_line");
                ddl.execute("drop function app." + trigger + "()");
            }
        }

        submit(owner, csvFile(file), null, key, token)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.totalAmount").value(14000));
        assertThat(count(owner, "app.product")).isEqualTo(1);
        assertThat(count(owner, "app.category")).isEqualTo(1);
        assertThat(count(owner, "app.purchase_receipt")).isEqualTo(1);
        assertThat(count(owner, "app.purchase_receipt_line")).isEqualTo(1);
        assertThat(count(owner, "app.stock_ledger")).isEqualTo(1);
        assertStock(owner, "ATOMIC", 2, 7000);
    }

    @Test
    void xlsxPreservesTextCodesAndReceivesNumericQuantityAndCost() throws Exception {
        Owner owner = newStore();
        byte[] file;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Phiếu nhập");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                header.createCell(i).setCellValue(HEADERS.get(i));
            }
            Row row = sheet.createRow(1);
            row.createCell(1).setCellValue("Sữa hộp");
            row.createCell(3).setCellValue("00042");
            row.createCell(4).setCellValue("0008934673123456");
            row.createCell(8).setCellValue(15000);
            row.createCell(9).setCellValue(12);
            row.createCell(10).setCellValue(10000);
            workbook.write(out);
            file = out.toByteArray();
        }
        JsonNode preview = preview(owner, xlsxFile(file), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].sku").value("00042"))
                .andExpect(jsonPath("$.rows[0].quantity").value(12))
                .andExpect(jsonPath("$.rows[0].unitCost").value(10000))
                .andExpect(jsonPath("$.rows[0].values.barcode").value("0008934673123456"))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(json::readTree);
        submit(owner, xlsxFile(file), null, UUID.randomUUID(), preview.get("previewToken").asString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(120000));
        assertStock(owner, "00042", 12, 10000);
        assertThat(asStore(owner, () -> jdbc.queryForObject(
                "select barcode from app.product_variant where sku = '00042'", String.class)))
                .isEqualTo("0008934673123456");
        assertThat(count(owner, "app.stock_ledger")).isEqualTo(1);
    }

    @Test
    void csvAndXlsxTemplatesDescribeReceiptQuantitiesAndNewProductFields() throws Exception {
        Owner owner = newStore();
        String csv = mvc.perform(get(RECEIPTS + "/import-template").cookie(owner.cookie()).param("format", "csv")
                        .header("Accept", "application/json, application/problem+json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Mã sản phẩm cha", "Tên Sản phẩm", "SKU", "Số lượng", "Giá nhập", "Giá bán");
        assertThat(csv).doesNotContain("Tồn đầu kỳ", "Giá vốn ban đầu");

        byte[] xlsx = mvc.perform(get(RECEIPTS + "/import-template").cookie(owner.cookie()).param("format", "xlsx")
                        .header("Accept", "application/json, application/problem+json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            List<String> headers = new ArrayList<>();
            workbook.getSheetAt(0).getRow(0).forEach(cell -> headers.add(cell.getStringCellValue().replace("*", "")));
            assertThat(headers).containsAll(HEADERS).doesNotContain("Tồn đầu kỳ", "Giá vốn ban đầu");
        }
        assertNothingImported(owner);
    }

    private ResultActions preview(Owner owner, MockMultipartFile file, String edits) throws Exception {
        return mvc.perform(importRequest(owner, IMPORT + "/preview", file, edits));
    }

    private ResultActions submit(Owner owner, MockMultipartFile file, String edits, UUID key, String token) throws Exception {
        return mvc.perform(importRequest(owner, IMPORT, file, edits)
                .header("Idempotency-Key", key).header("X-Import-Preview", token));
    }

    private MockMultipartHttpServletRequestBuilder importRequest(Owner owner, String path, MockMultipartFile file,
                                                                String edits) {
        var request = multipart(path).file(file).cookie(owner.cookie())
                .param("supplierId", owner.supplierId().toString()).param("note", "Nhập từ file thử nghiệm");
        if (edits != null) {
            request.param("edits", edits);
        }
        return request;
    }

    private static MockMultipartFile csvFile(byte[] content) {
        return new MockMultipartFile("file", "phieu-nhap.csv", "text/csv", content);
    }

    private static MockMultipartFile xlsxFile(byte[] content) {
        return new MockMultipartFile("file", "phieu-nhap.xlsx", XLSX, content);
    }

    @SafeVarargs
    private static byte[] csv(List<String>... rows) {
        StringBuilder content = new StringBuilder("\ufeff");
        appendCsv(content, HEADERS);
        for (List<String> row : rows) {
            appendCsv(content, row);
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCsv(StringBuilder content, List<String> cells) {
        content.append(cells.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
    }

    private Product createProduct(Owner owner, Map<String, Object> body) throws Exception {
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie()).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode product = json.readTree(response);
        return new Product(UUID.fromString(product.get("id").asString()),
                UUID.fromString(product.get("variants").get(0).get("id").asString()));
    }

    private Owner newStore() throws Exception {
        String content = json.writeValueAsString(Map.of("storeName", "Cửa hàng thử nhập phiếu", "displayName", "Chủ thử nhập",
                "loginKey", "receipt." + UUID.randomUUID().toString().replace("-", "") + "@test.vn", "password", "matkhau123"));
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isCreated()).andReturn().getResponse();
        Cookie cookie = response.getCookie("access_token");
        UUID storeId = UUID.fromString(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("storeId").asString());
        String supplier = mvc.perform(post("/api/v1/catalog/suppliers").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nhà cung cấp thử nhập file\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Owner(cookie, storeId, UUID.fromString(json.readTree(supplier).get("supplierId").asString()));
    }

    private void assertStock(Owner owner, String sku, int quantity, long cost) {
        assertThat(asStore(owner, () -> jdbc.queryForMap("""
                select s.quantity, s.avg_unit_cost from app.stock s
                join app.product_variant v on v.id = s.variant_id where v.sku = ?
                """, sku))).containsEntry("quantity", quantity).containsEntry("avg_unit_cost", cost);
    }

    private void assertNothingImported(Owner owner) {
        for (String table : List.of("app.product", "app.product_variant", "app.category", "app.stock", "app.stock_ledger",
                "app.purchase_receipt", "app.purchase_receipt_line")) {
            assertThat(count(owner, table)).as(table).isZero();
        }
    }

    private long count(Owner owner, String table) {
        return asStore(owner, () -> jdbc.queryForObject("select count(*) from " + table, Long.class));
    }

    private <T> T asStore(Owner owner, Supplier<T> work) {
        return TenantScope.runAs(owner.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> work.get()));
    }
}
