package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Nhập Sản phẩm từ Excel (FR-6, SP-06) trên PostgreSQL thật: ma trận I/O của spec. File {@code .xlsx} dựng ngay trong
 * test bằng Apache POI. Mỗi test dùng Cửa hàng mới đăng ký riêng. Sau khi nhập kiểm cả Tồn kho do {@code inventory}
 * ghi từ {@link VariantCreated} (dòng {@code stock}, Tồn đầu kỳ {@code OPENING}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class ProductImportIntegrationTest {

    static final String IMPORT = "/api/v1/catalog/products/import";
    static final String TEMPLATE = "/api/v1/catalog/products/import-template";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** Tiêu đề của file mẫu, đúng thứ tự. */
    static final List<String> HEADERS = List.of("Tên Sản phẩm*", "Danh mục", "Đơn vị tính", "Giá bán*", "Tồn đầu kỳ",
            "Giá vốn ban đầu", "SKU", "Mã vạch", "Mô tả");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired ApplicationEvents events;
    @Autowired PasswordEncoder passwords;

    record Owner(Cookie cookie, UUID storeId) {
    }

    /** Ô công thức: giá trị Excel đã tính được lưu kèm (như file Excel lưu thật). */
    record Formula(String expression) {
    }

    // --- File mẫu ------------------------------------------------------------------------------------------

    @Test
    void theTemplateHasTheHeadersAndAnExampleRowThatIsSkippedUnlessEdited() throws Exception {
        Owner owner = newStore();
        // Accept như client API của frontend (shared/api/client.ts): vẫn nhận file, không 406
        MvcResult response = mvc.perform(get(TEMPLATE).cookie(owner.cookie())
                        .header("Accept", "application/json, application/problem+json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"mau-nhap-san-pham.xlsx\""))
                .andReturn();
        byte[] template = response.getResponse().getContentAsByteArray();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Sản phẩm");
            List<String> headers = new ArrayList<>();
            sheet.getRow(0).forEach(cell -> headers.add(cell.getStringCellValue()));
            assertThat(headers).isEqualTo(HEADERS);
            // Ghi chú cột bắt buộc ngay ở ô tiêu đề
            assertThat(sheet.getRow(0).getCell(0).getCellComment().getString().getString()).contains("Bắt buộc");
            assertThat(sheet.getRow(0).getCell(3).getCellComment().getString().getString()).contains("Bắt buộc");
            // Một dòng ví dụ; ô số định dạng số, mã vạch là chữ (không mất số 0 đầu, không thành 8,9E+12)
            Row example = sheet.getRow(1);
            assertThat(example.getCell(0).getStringCellValue()).isEqualTo("Sữa tươi Vinamilk 1L");
            assertThat(example.getCell(3).getNumericCellValue()).isEqualTo(32000);
            assertThat(example.getCell(3).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(example.getCell(7).getStringCellValue()).isEqualTo("8934673123456");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("Hướng dẫn")).isNotNull();
        }

        // Nhập nguyên file mẫu: dòng ví dụ còn nguyên thì bỏ qua (Sản phẩm không xóa được), báo lỗi không có cột
        String body = importFile(owner, template)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].row").value(2))
                .andExpect(jsonPath("$.failed[0].message").value(
                        "Dòng ví dụ của file mẫu, không nhập. Xóa dòng này hoặc sửa thành Sản phẩm của bạn."))
                .andExpect(jsonPath("$.createdCategories.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode column = json.readTree(body).get("failed").get(0).get("column");
        assertThat(column == null || column.isNull()).as("column của dòng ví dụ").isTrue();
        assertThat(count(owner, "app.product")).isZero();
        assertThat(count(owner, "app.category")).isZero();

        // File mẫu vẫn dùng được: sửa một ô của dòng ví dụ là thành Sản phẩm thật, Danh mục "Đồ uống" được tạo
        byte[] edited;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getSheetAt(0).getRow(1).getCell(0).setCellValue("Sữa tươi Vinamilk 1L ít đường");
            workbook.write(out);
            edited = out.toByteArray();
        }
        importFile(owner, edited)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.createdCategories[0]").value("Đồ uống"));
        Map<String, Object> stock = stockOf(owner, "VNM-1L");
        assertThat(stock).containsEntry("quantity", 24).containsEntry("avg_unit_cost", 27000L);
    }

    // --- Nhập thành công ---------------------------------------------------------------------------------

    @Test
    void threeValidRowsAreImportedWithTheirCategoriesAndOpeningStock() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        byte[] file = xlsx(HEADERS,
                cells("Trà xanh 0 độ", " đồ  UỐNG ", "chai", 10000, 24, 7000, "tx-01", "8934563138165", null),
                cells("Bánh quy Cosy", "Bánh kẹo", "gói", "25.000", null, null, null, null, "Dòng 1\nDòng 2"),
                cells("Kẹo dẻo", "bánh KẸO", null, 5000, "2.000", "3.500", null, null, null));

        importFile(owner, file)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.createdCategories.length()").value(1))
                .andExpect(jsonPath("$.createdCategories[0]").value("Bánh kẹo"));

        // 3 VariantCreated, đúng Tồn đầu kỳ, Giá vốn ban đầu
        assertThat(events.stream(VariantCreated.class).map(e -> e.openingQuantity() + "x" + e.openingUnitCost()))
                .containsExactly("24x7000", "0x0", "2000x3500");
        assertThat(events.stream(VariantCreated.class).map(VariantCreated::storeId)).containsOnly(owner.storeId());

        Map<Object, Map<String, Object>> byName = new LinkedHashMap<>();
        asStore(owner.storeId(), () -> jdbc.queryForList("""
                select p.name, c.name as category, p.unit, p.description, v.sku, v.barcode, v.sale_price
                from app.product p
                join app.product_variant v on v.product_id = p.id and v.is_default
                left join app.category c on c.id = p.category_id
                """)).forEach(r -> byName.put(r.get("name"), r));
        assertThat(byName).containsOnlyKeys("Trà xanh 0 độ", "Bánh quy Cosy", "Kẹo dẻo");
        assertThat(byName.get("Trà xanh 0 độ")).containsEntry("category", "Đồ uống").containsEntry("unit", "chai")
                .containsEntry("sku", "TX-01").containsEntry("barcode", "8934563138165")
                .containsEntry("sale_price", 10000L);
        assertThat(byName.get("Bánh quy Cosy")).containsEntry("category", "Bánh kẹo")
                .containsEntry("description", "Dòng 1\nDòng 2").containsEntry("sale_price", 25000L);
        assertThat((String) byName.get("Bánh quy Cosy").get("sku")).matches("SP-[0-9A-F]{8}");
        assertThat(byName.get("Kẹo dẻo")).containsEntry("category", "Bánh kẹo").containsEntry("unit", "cái");
        // "Bánh kẹo" tạo một lần dù hai dòng dùng; "đồ  UỐNG " là Danh mục có sẵn
        List<Map<String, Object>> categories = asStore(owner.storeId(),
                () -> jdbc.queryForList("select id, name from app.category order by name"));
        assertThat(categories).extracting(c -> c.get("name")).containsExactly("Bánh kẹo", "Đồ uống");
        assertThat(categories.get(1)).containsEntry("id", drinks);

        // inventory đã ghi Tồn kho trong cùng transaction
        assertThat(stockOf(owner, "TX-01")).containsEntry("quantity", 24).containsEntry("avg_unit_cost", 7000L);
        Long ledger = asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.stock_ledger where document_type = 'OPENING'", Long.class));
        assertThat(ledger).isEqualTo(2);
        Long stockRows = asStore(owner.storeId(), () -> jdbc.queryForObject("select count(*) from app.stock",
                Long.class));
        assertThat(stockRows).isEqualTo(3);
    }

    @Test
    void columnsAreReadByHeaderNameAndBlankRowsAreSkipped() throws Exception {
        Owner owner = newStore();
        List<String> headers = List.of("Ghi chú", "  GIÁ BÁN* ", "SKU", "tên sản phẩm");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(cells("bỏ qua", 1000, "A-1", "Hàng một"));
        rows.add(null);                                   // dòng 3 không có ô nào
        rows.add(cells(null, "   ", null, "\u00a0"));  // dòng 4 chỉ có khoảng trắng
        rows.add(cells("chỉ ghi chú", null, null, null)); // dòng 5: cột lạ không tính
        rows.add(cells(null, null, "A-2", "Hàng thiếu giá"));
        byte[] file = xlsx(headers, rows);

        importFile(owner, file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].row").value(6))
                .andExpect(jsonPath("$.failed[0].column").value("Giá bán"))
                .andExpect(jsonPath("$.failed[0].message").value("Nhập giá bán"));
    }

    @Test
    void numbersAreAcceptedAsNumberCellsOrVietnameseTextButNotAsFractions() throws Exception {
        Owner owner = newStore();
        byte[] file = xlsx(List.of("Tên Sản phẩm", "Giá bán", "Tồn đầu kỳ", "Giá vốn ban đầu"),
                cells("Chữ có dấu chấm", "15.000", null, null),                 // 2
                cells("Ô số", 15000, null, null),                              // 3
                cells("Phẩy lẻ", "1,5", null, null),                           // 4
                cells("Ô số lẻ", 1.5, null, null),                             // 5
                cells("Không phải số", "mười nghìn", null, null),              // 6
                cells("Âm", "-5", null, null),                                 // 7
                cells("Phẩy hàng nghìn", "15,000", null, null),                // 8
                cells("Phần lẻ bằng 0", "16.000,00", " 1 200 ", "9.000"),      // 9
                cells("Công thức", new Formula("1000*2"), null, null),         // 10
                cells("Công thức lỗi", new Formula("1/0"), null, null),        // 11
                cells("Tồn quá lớn", 1000, "3000000000", 1));                  // 12

        String body = importFile(owner, file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(4))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(failures(body)).containsExactly(
                "4|Giá bán|Giá bán phải là số nguyên",
                "5|Giá bán|Giá bán phải là số nguyên",
                "6|Giá bán|Giá bán phải là số, ví dụ 15000 hoặc 15.000",
                "7|Giá bán|Giá bán không được âm",
                "8|Giá bán|Giá bán viết dấu chấm để ngăn cách hàng nghìn (ví dụ 15.000); dấu phẩy là dấu thập phân",
                "11|Giá bán|Ô Giá bán có lỗi công thức",
                "12|Tồn đầu kỳ|Tồn kho đầu kỳ quá lớn");
        List<Map<String, Object>> imported = asStore(owner.storeId(), () -> jdbc.queryForList("""
                select p.name, v.sale_price from app.product p join app.product_variant v on v.product_id = p.id
                order by p.created_at, p.name
                """));
        assertThat(imported).extracting(r -> r.get("name") + "=" + r.get("sale_price")).containsExactlyInAnyOrder(
                "Chữ có dấu chấm=15000", "Ô số=15000", "Phần lẻ bằng 0=16000", "Công thức=2000");
        // " 1 200 " là 1200, "9.000" là 9000
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForMap(
                "select delta, unit_cost from app.stock_ledger where document_type = 'OPENING'")))
                .containsEntry("delta", 1200).containsEntry("unit_cost", 9000L);
    }

    // --- Dòng lỗi lẫn dòng đúng ----------------------------------------------------------------------------

    @Test
    void invalidRowsAreReportedWithTheirRowAndColumnWhileValidRowsAreImported() throws Exception {
        Owner owner = newStore();
        createProduct(owner, "Bia Hà Nội", "BIA-HN", "8935001800019");
        byte[] file = xlsx(HEADERS,
                product("Nước suối", 5000, null, null),                                   // 2
                product("Thiếu giá", null, null, null),                                   // 3
                product("Bánh mì", 8000, "BM-01", null),                                  // 4
                product("Trùng mã vạch", 12000, null, "8935001800019"),                   // 5
                cells("Tồn mà không vốn", null, null, 1000, 10, null, null, null, null),  // 6
                product("Trùng SKU có sẵn", 9000, "bia-hn", null),                        // 7
                cells(null, "Đồ  ăn vặt", null, "1,5", null, null, null, "ab-12", null)); // 8

        String body = importFile(owner, file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(failures(body)).containsExactly(
                "3|Giá bán|Nhập giá bán",
                "5|Mã vạch|Mã vạch 8935001800019 đã có ở Sản phẩm khác của Cửa hàng",
                "6|Giá vốn ban đầu|Có Tồn kho đầu kỳ thì nhập Giá vốn ban đầu lớn hơn 0",
                "7|SKU|SKU BIA-HN đã có ở Sản phẩm khác của Cửa hàng",
                // Mọi lỗi của một dòng trong một lần, theo thứ tự cột
                "8|Tên Sản phẩm|Nhập tên Sản phẩm",
                "8|Giá bán|Giá bán phải là số nguyên",
                "8|Mã vạch|Mã vạch chỉ gồm chữ cái không dấu và chữ số");
        assertThat(json.readTree(body).get("createdCategories").size()).isZero();
        // Danh mục chỉ có ở dòng lỗi thì không được tạo
        assertThat(count(owner, "app.category")).isZero();
        assertThat(count(owner, "app.product")).isEqualTo(3);
    }

    @Test
    void aNumberCellInATextColumnIsReadAsItsDigits() throws Exception {
        Owner owner = newStore();
        // Mã vạch gõ vào ô định dạng số: Excel lưu 8.934563138165E12
        importFile(owner, xlsx(HEADERS, cells("Mã vạch là ô số", null, null, 1000, null, null, null, 8934563138165L,
                null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject("select barcode from app.product_variant",
                String.class))).isEqualTo("8934563138165");
    }

    @Test
    void invalidCategoryNamesAreReportedOnTheCategoryColumnAndNotCreated() throws Exception {
        Owner owner = newStore();
        String body = importFile(owner, xlsx(HEADERS,
                cells("Danh mục dài", "a".repeat(101), null, 1000, null, null, null, null, null),
                cells("Danh mục xuống dòng", "Đồ\nuống", null, 1000, null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(failures(body)).containsExactly(
                "2|Danh mục|Tên Danh mục tối đa 100 ký tự",
                "3|Danh mục|Tên Danh mục có ký tự không hợp lệ");
        assertThat(count(owner, "app.category")).isZero();
        assertThat(count(owner, "app.product")).isZero();
    }

    @Test
    void duplicatesInTheSameFileKeepTheFirstValidRow() throws Exception {
        Owner owner = newStore();
        byte[] file = xlsx(HEADERS,
                product("Dòng 2", 1000, "SKU-1", "111"),      // 2: nhập
                product("Dòng 3 lỗi", null, "SKU-2", "222"),  // 3: thiếu giá, không giữ mã
                product("Dòng 4", 1000, "sku-1", null),       // 4: trùng SKU dòng 2
                product("Dòng 5", 1000, "SKU-2", "111"),      // 5: trùng mã vạch dòng 2
                product("Dòng 6", 1000, "SKU-3", "222"));     // 6: nhập (dòng 3 lỗi)

        String body = importFile(owner, file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(failures(body)).containsExactly(
                "3|Giá bán|Nhập giá bán",
                "4|SKU|SKU SKU-1 trùng với dòng 2",
                "5|Mã vạch|Mã vạch 111 trùng với dòng 2");
        List<String> names = asStore(owner.storeId(),
                () -> jdbc.queryForList("select name from app.product order by name", String.class));
        assertThat(names).containsExactly("Dòng 2", "Dòng 6");
    }

    @Test
    void codesOfSoftDeletedVariantsCanBeReused() throws Exception {
        Owner owner = newStore();
        createProduct(owner, "Hàng cũ", "CU-1", "999");
        asStore(owner.storeId(), () -> jdbc.update("update app.product_variant set deleted_at = now()"));
        importFile(owner, xlsx(HEADERS, product("Hàng mới", 1000, "cu-1", "999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void aFileWithoutAnyValidRowStillReturns200() throws Exception {
        Owner owner = newStore();
        importFile(owner, xlsx(HEADERS, product("Thiếu giá", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.failed[0].row").value(2))
                .andExpect(jsonPath("$.createdCategories.length()").value(0));
        // Chỉ có dòng tiêu đề: không có gì để nhập
        importFile(owner, xlsx(HEADERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.failed.length()").value(0));
        assertThat(count(owner, "app.product")).isZero();
    }

    // --- File sai -----------------------------------------------------------------------------------------

    @Test
    void aMissingRequiredHeaderRejectsTheWholeFile() throws Exception {
        Owner owner = newStore();
        importFile(owner, xlsx(List.of("Tên Sản phẩm", "SKU"), cells("Không có giá", "X-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/catalog/import-invalid-file"))
                .andExpect(jsonPath("$.detail").value(containsString("Giá bán")));
        importFile(owner, xlsx(List.of("Mô tả"), cells("Chỉ có mô tả")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Tên Sản phẩm, Giá bán")));
        importFile(owner, xlsx(List.of("Tên Sản phẩm", "Giá bán", "giá bán*"), cells("Cột lặp", 1, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/catalog/import-invalid-file"))
                .andExpect(jsonPath("$.detail").value(containsString("lặp")));
        assertThat(count(owner, "app.product")).isZero();
    }

    @Test
    void filesThatAreNotXlsxAreRejected() throws Exception {
        Owner owner = newStore();
        byte[] valid = xlsx(HEADERS, product("Hàng", 1000, null, null));
        List<byte[]> invalid = List.of(
                "Tên Sản phẩm,Giá bán\nHàng,1000\n".getBytes(StandardCharsets.UTF_8),          // .csv
                new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
                new byte[0],
                Arrays.copyOf(valid, 200),                                                      // bị cắt
                zip("ghi-chu.txt", "không phải Excel"),                                         // ZIP lạ
                zip("[Content_Types].xml", "<Types/>"));                                        // OOXML hỏng
        for (byte[] content : invalid) {
            importFile(owner, content)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/catalog/import-invalid-file"));
        }
        importFile(owner, invalid.get(1)).andExpect(jsonPath("$.detail").value(containsString(".xls")));
        assertThat(count(owner, "app.product")).isZero();
    }

    @Test
    void moreThanOneThousandDataRowsAreRejected() throws Exception {
        Owner owner = newStore();
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            rows.add(product("Hàng " + i, null, null, null));
        }
        // Đúng 1000 dòng (dòng trống không tính): đọc được, mọi dòng lỗi thiếu giá
        List<List<Object>> withBlank = new ArrayList<>(rows);
        withBlank.add(500, null);
        importFile(owner, xlsx(HEADERS, withBlank))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.failed.length()").value(1000))
                .andExpect(jsonPath("$.failed[999].row").value(1002));

        rows.add(product("Hàng 1001", 1000, null, null));
        importFile(owner, xlsx(HEADERS, rows))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/catalog/import-invalid-file"))
                .andExpect(jsonPath("$.detail").value(containsString("1000")));
        assertThat(count(owner, "app.product")).isZero();
    }

    @Test
    void oneThousandValidRowsAreImportedInOneTransaction() throws Exception {
        Owner owner = newStore();
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            rows.add(product("Hàng " + i, 1000 + i, "MA-" + i, null));
        }
        importFile(owner, xlsx(HEADERS, rows))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1000))
                .andExpect(jsonPath("$.failed.length()").value(0));
        assertThat(events.stream(VariantCreated.class)).hasSize(1000);
        assertThat(count(owner, "app.product")).isEqualTo(1000);
        assertThat(count(owner, "app.stock")).isEqualTo(1000);
    }

    @Test
    void aFileOverTwoMegabytesIsRejected() throws Exception {
        Owner owner = newStore();
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(new byte[] {'P', 'K', 3, 4}, 0, big, 0, 4);
        importFile(owner, big)
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.type").value("/errors/common/payload-too-large"));
        mvc.perform(multipart(IMPORT).cookie(owner.cookie()).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    // --- Idempotency-Key ------------------------------------------------------------------------------------

    @Test
    void sendingTheSameFileAgainWithTheSameKeyReplaysTheFirstResult() throws Exception {
        Owner owner = newStore();
        byte[] file = xlsx(HEADERS,
                cells("Hàng A", "Danh mục mới", null, 1000, 5, 800, null, null, null),
                product("Thiếu giá", null, null, null));
        String key = UUID.randomUUID().toString();
        String first = importFile(owner, key, file)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String again = importFile(owner, key, file)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
        assertThat(json.readTree(first).get("createdCategories").get(0).asString()).isEqualTo("Danh mục mới");
        assertThat(count(owner, "app.product")).isEqualTo(1);
        assertThat(count(owner, "app.category")).isEqualTo(1);
        assertThat(events.stream(VariantCreated.class)).hasSize(1);
        assertThat(stockOf(owner, null)).containsEntry("quantity", 5);

        // Cùng khóa, file khác: 409; thiếu khóa, khóa không phải UUID: 422 (sau lỗi tệp)
        importFile(owner, key, xlsx(HEADERS, product("Hàng khác", 1000, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-reuse"));
        importFile(owner, null, file)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-missing"));
        importFile(owner, "khong-phai-uuid", file)
                .andExpect(status().isUnprocessableContent());
        importFile(owner, null, "khong-phai-excel".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/catalog/import-invalid-file"));
        assertThat(count(owner, "app.product")).isEqualTo(1);
    }

    // --- Lỗi bất ngờ khi ghi ----------------------------------------------------------------------------------

    /**
     * Người khác tạo cùng mã vạch sau lúc kiểm, trước lúc ghi: lần nhập chờ khóa của transaction kia rồi gặp unique,
     * cả lần nhập rollback (kể cả Sản phẩm của dòng trước và Danh mục vừa tạo), trả {@code 409} nêu dòng.
     */
    @Test
    void aCodeTakenByAnotherWriterDuringTheImportRollsBackTheWholeImport() throws Exception {
        Owner owner = newStore();
        byte[] file = xlsx(HEADERS,
                cells("Hàng A", "Danh mục mới", null, 1000, 3, 500, null, "1111", null),
                product("Hàng B", 2000, null, "2222"));
        String key = UUID.randomUUID().toString();
        MvcResult result = importWhileAnotherWriterHoldsALock(owner, key, file, () -> {
            UUID productId = UUID.randomUUID();
            jdbc.update("insert into app.product (id, store_id, name) values (?, ?, 'Hàng người khác')",
                    productId, owner.storeId());
            jdbc.update("""
                    insert into app.product_variant (id, store_id, product_id, sku, barcode, sale_price, is_default)
                    values (?, ?, ?, 'KHAC-1', '2222', 1000, true)
                    """, UUID.randomUUID(), owner.storeId(), productId);
        });
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.get("type").asString()).isEqualTo("/errors/catalog/barcode-taken");
        assertThat(problem.get("detail").asString()).startsWith("Dòng 3: ");

        List<String> names = asStore(owner.storeId(),
                () -> jdbc.queryForList("select name from app.product", String.class));
        assertThat(names).containsExactly("Hàng người khác");
        assertThat(count(owner, "app.category")).isZero();
        assertThat(count(owner, "app.product_import")).isZero();
        assertThat(count(owner, "app.stock_ledger")).isZero();
        // Không có chứng từ nào giữ khóa: gửi lại cùng khóa sau khi sửa file là một lần nhập mới
        importFile(owner, key, xlsx(HEADERS, product("Hàng A", 1000, null, "1111")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.imported").value(1));
    }

    /**
     * Người khác tạo Danh mục cùng tên (khác hoa thường) sau lúc kiểm, trước lúc ghi: lần nhập chờ ở
     * {@code category_name_uq} rồi gặp unique, cả lần nhập rollback, trả {@code 409 category-name-taken} nêu dòng.
     */
    @Test
    void aCategoryCreatedByAnotherWriterDuringTheImportRollsBackTheWholeImport() throws Exception {
        Owner owner = newStore();
        byte[] file = xlsx(HEADERS,
                cells("Hàng A", "Danh mục chung", null, 1000, 3, 500, null, null, null),
                cells("Hàng B", "Danh mục chung", null, 2000, null, null, null, null, null));
        MvcResult result = importWhileAnotherWriterHoldsALock(owner, UUID.randomUUID().toString(), file,
                () -> jdbc.update("insert into app.category (store_id, name) values (?, 'danh mục chung')",
                        owner.storeId()));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.get("type").asString()).isEqualTo("/errors/catalog/category-name-taken");
        assertThat(problem.get("detail").asString()).startsWith("Dòng 2: ");

        assertThat(count(owner, "app.product")).isZero();
        assertThat(count(owner, "app.product_import")).isZero();
        assertThat(count(owner, "app.stock_ledger")).isZero();
        // Chỉ còn Danh mục của người kia
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForList("select name from app.category", String.class)))
                .containsExactly("danh mục chung");
    }

    /**
     * Chạy {@code write} trong một transaction khác của Cửa hàng và giữ nó mở (chưa commit), gửi lần nhập, chờ tới khi
     * lần nhập đứng chờ khóa của transaction đó (unique), rồi mới commit; trả phản hồi của lần nhập.
     */
    MvcResult importWhileAnotherWriterHoldsALock(Owner owner, String key, byte[] file, Runnable write)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> asStore(owner.storeId(), () -> {
                write.run();
                written.countDown();
                await(release);
                return null;
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> importing = pool.submit(() -> importFile(owner, key, file).andReturn());
            awaitLockWait();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            return importing.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // --- Quyền -------------------------------------------------------------------------------------------------

    @Test
    void staffCanNeitherDownloadTheTemplateNorImport() throws Exception {
        Owner owner = newStore();
        Cookie staff = addStaff(owner);
        mvc.perform(get(TEMPLATE).cookie(staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(multipart(IMPORT).file(xlsxPart(xlsx(HEADERS, product("Hàng", 1000, null, null))))
                        .cookie(staff).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        assertThat(count(owner, "app.product")).isZero();
    }

    // --- Tiện ích ---------------------------------------------------------------------------------------------

    /** Dòng theo cột của file mẫu, chỉ với tên, giá, SKU, mã vạch. */
    static List<Object> product(String name, Object salePrice, String sku, String barcode) {
        return cells(name, null, null, salePrice, null, null, sku, barcode, null);
    }

    static List<Object> cells(Object... values) {
        return Arrays.asList(values);
    }

    @SafeVarargs
    static byte[] xlsx(List<String> headers, List<Object>... rows) {
        return xlsx(headers, Arrays.asList(rows));
    }

    /** File {@code .xlsx}: dòng 1 là {@code headers}; mỗi phần tử {@code null} của {@code rows} là một dòng trống. */
    static byte[] xlsx(List<String> headers, List<List<Object>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sản phẩm");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                List<Object> values = rows.get(r);
                if (values == null) {
                    continue;
                }
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < values.size(); c++) {
                    Object value = values.get(c);
                    if (value == null) {
                        continue;
                    }
                    Cell cell = row.createCell(c);
                    switch (value) {
                        case Number n -> cell.setCellValue(n.doubleValue());
                        case Formula f -> cell.setCellFormula(f.expression());
                        default -> cell.setCellValue(value.toString());
                    }
                }
            }
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] zip(String entry, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    static MockMultipartFile xlsxPart(byte[] content) {
        return new MockMultipartFile("file", "san-pham.xlsx", XLSX, content);
    }

    ResultActions importFile(Owner owner, byte[] content) throws Exception {
        return importFile(owner, UUID.randomUUID().toString(), content);
    }

    ResultActions importFile(Owner owner, String key, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(IMPORT).file(xlsxPart(content)).cookie(owner.cookie());
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    /** {@code failed} của phản hồi dạng {@code "dòng|cột|lý do"}. */
    List<String> failures(String body) {
        List<String> out = new ArrayList<>();
        json.readTree(body).get("failed").forEach(f -> out.add(f.get("row").asInt() + "|" + f.get("column").asString()
                + "|" + f.get("message").asString()));
        return out;
    }

    /** Tồn kho của Biến thể có SKU {@code sku} ({@code null}: Biến thể duy nhất của Cửa hàng). */
    Map<String, Object> stockOf(Owner owner, String sku) {
        return asStore(owner.storeId(), () -> jdbc.queryForMap("""
                select s.quantity, s.avg_unit_cost from app.stock s
                join app.product_variant v on v.id = s.variant_id
                where (?::text is null or v.sku = ?)
                """, sku, sku));
    }

    long count(Owner owner, String table) {
        Long n = asStore(owner.storeId(), () -> jdbc.queryForObject("select count(*) from " + table, Long.class));
        return n == null ? 0 : n;
    }

    /** Chờ tới khi có một kết nối đang chờ khóa (lần nhập đứng ở unique, chờ transaction kia commit). */
    void awaitLockWait() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Long waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock' and state = 'active'
                    """, Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Lần nhập không chờ khóa của transaction kia");
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Hết thời gian chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    void createProduct(Owner owner, String name, String sku, String barcode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("salePrice", 10000);
        body.put("sku", sku);
        body.put("barcode", barcode);
        mvc.perform(post("/api/v1/catalog/products").cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    UUID createCategory(Owner owner, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/catalog/categories").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử nhập Excel");
        body.put("loginKey", "nhap." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        UUID storeId = UUID.fromString(json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("storeId").asString());
        return new Owner(response.getCookie("access_token"), storeId);
    }

    Cookie addStaff(Owner owner) throws Exception {
        String loginKey = "nv." + UUID.randomUUID().toString().replace("-", "") + "@test.vn";
        asStore(owner.storeId(), () -> jdbc.update("""
                insert into app.app_user (store_id, login_key, display_name, password_hash, role)
                values (?, ?, 'Nhân viên thử', ?, 'STAFF')
                """, owner.storeId(), loginKey, passwords.encode("matkhau123")));
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", loginKey, "password", "matkhau123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
