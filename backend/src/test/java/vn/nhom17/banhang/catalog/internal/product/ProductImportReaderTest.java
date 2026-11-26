package vn.nhom17.banhang.catalog.internal.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import vn.nhom17.banhang.catalog.internal.product.ProductImportReader.ImportRow;
import vn.nhom17.banhang.platform.ProblemException;

/** Quy tắc đọc số viết bằng chữ, tiêu đề cột và byte đầu tệp của nhập Excel (SP-06). */
class ProductImportReaderTest {

    @Test
    void csvAcceptsUtf8BomQuotesEscapesMultilineAndKeepsRecordStartLines() {
        byte[] csv = ("\ufeffTên Sản phẩm,Giá bán,SKU,Mô tả\r\n"
                + "\"Trà, sữa\",15.000,000123,\"Dòng 1\r\nDòng \"\"hai\"\"\"\r\n"
                + ",,,\r\n"
                + "Hàng sau,2000,SAU,\r\n").getBytes(StandardCharsets.UTF_8);

        List<ImportRow> rows = ProductImportReader.read(csv, "san-pham.CSV");

        assertThat(rows).extracting(ImportRow::row).containsExactly(2, 5);
        assertThat(rows.getFirst().texts()).containsEntry(ImportColumn.NAME, "Trà, sữa")
                .containsEntry(ImportColumn.SKU, "000123")
                .containsEntry(ImportColumn.DESCRIPTION, "Dòng 1\nDòng \"hai\"");
        assertThat(rows.getFirst().numbers().get(ImportColumn.SALE_PRICE)).isEqualByComparingTo("15000");
    }

    @Test
    void csvAlsoAcceptsExcelSemicolonAndVietnameseDecimalText() {
        List<ImportRow> rows = ProductImportReader.readCsv(("Tên Sản phẩm;Giá bán;Mã sản phẩm cha;Tên Biến thể\n"
                + "Áo;1,5;AO;S\n").getBytes(StandardCharsets.UTF_8));
        assertThat(rows.getFirst().numbers().get(ImportColumn.SALE_PRICE)).isEqualByComparingTo("1.5");
        assertThat(rows.getFirst().texts()).containsEntry(ImportColumn.PARENT_CODE, "AO");
    }

    @Test
    void csvRejectsBrokenQuotingInvalidUtf8AndDuplicateOrMissingHeaders() {
        for (String csv : List.of("Tên Sản phẩm,Giá bán\n\"Hàng\nchưa đóng,1",
                "Tên Sản phẩm,Giá bán\n\"Hàng\"sai,1", "Tên Sản phẩm,Giá bán\nHà\"ng,1",
                "Tên Sản phẩm,Giá bán,giá bán*\nHàng,1,2", "Tên Sản phẩm,SKU\nHàng,X")) {
            assertThatThrownBy(() -> ProductImportReader.readCsv(csv.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOfSatisfying(ProblemException.class,
                            p -> assertThat(p.type()).isEqualTo(ProductImportReader.INVALID_FILE));
        }
        assertThatThrownBy(() -> ProductImportReader.readCsv(new byte[] {(byte) 0xC3, 0x28}))
                .isInstanceOf(ProblemException.class).hasMessageContaining("UTF-8");
    }

    @Test
    void csvLimitsNonblankRecordsInsteadOfPhysicalLines() {
        String header = "Tên Sản phẩm,Giá bán,Mô tả\n";
        String row = "Hàng,1000,\"Dòng 1\nDòng 2\"\n\n";
        assertThat(ProductImportReader.readCsv((header + row.repeat(1000)).getBytes(StandardCharsets.UTF_8)))
                .hasSize(1000);
        assertThatThrownBy(() -> ProductImportReader.readCsv((header + row.repeat(1001))
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(ProblemException.class).hasMessageContaining("1000");
    }

    @Test
    void bothTemplatesKeepTheSimpleExampleAndLeaveOptionalVariantColumnsBlank() {
        List<ImportRow> csv = ProductImportReader.readCsv(ProductImportTemplate.buildCsv());
        List<ImportRow> xlsx = ProductImportReader.read(ProductImportTemplate.build());
        assertThat(csv).hasSize(1);
        assertThat(xlsx).hasSize(1);
        assertThat(ProductImportTemplate.isExample(csv.getFirst())).isTrue();
        assertThat(ProductImportTemplate.isExample(xlsx.getFirst())).isTrue();
        assertThat(csv.getFirst().texts()).doesNotContainKey(ImportColumn.PARENT_CODE);
    }

    @Test
    void numbersWrittenAsTextFollowTheVietnameseConvention() {
        assertThat(ProductImportReader.parseNumber("15000")).isEqualByComparingTo("15000");
        assertThat(ProductImportReader.parseNumber("15.000")).isEqualByComparingTo("15000");
        assertThat(ProductImportReader.parseNumber("1.234.567")).isEqualByComparingTo("1234567");
        assertThat(ProductImportReader.parseNumber(" 1 200\u00a0")).isEqualByComparingTo("1200");
        assertThat(ProductImportReader.parseNumber("007")).isEqualByComparingTo("7");
        // Phần lẻ giữ nguyên để kiểm báo "phải là số nguyên"; phần lẻ bằng 0 vẫn là số nguyên
        assertThat(ProductImportReader.parseNumber("1,5")).isEqualByComparingTo("1.5");
        assertThat(ProductImportReader.parseNumber("15.000,00")).isEqualByComparingTo("15000");
        // Dấu trừ giữ nguyên để kiểm báo "không được âm"
        assertThat(ProductImportReader.parseNumber("-5")).isEqualByComparingTo(BigDecimal.valueOf(-5));
    }

    @Test
    void textThatIsNotAVietnameseNumberIsRejected() {
        for (String text : new String[] {"", "abc", "15k", "1.5", "15.00", "1.2345", "15,000", "1,500", "15.000,000",
                "--5", "+5", "1e5", "đ", "15đđ", "15 đồng", "15 USD", "đ 15"}) {
            assertThat(ProductImportReader.parseNumber(text)).as(text).isNull();
        }
    }

    @Test
    void oneTrailingCurrencyMarkAndInvisibleCharactersAreDropped() {
        for (String text : new String[] {"15.000đ", "15.000 ₫", "15.000 VNĐ", "15000 vnd", "15.000 Đ", "15.000Vnđ"}) {
            assertThat(ProductImportReader.parseNumber(text)).as(text).isEqualByComparingTo("15000");
        }
        // Ký tự định dạng vô hình dán kèm số (U+200B, U+FEFF), khoảng trắng không ngắt (U+00A0) trước ký hiệu tiền
        assertThat(ProductImportReader.parseNumber("\u200b15.000\ufeff")).isEqualByComparingTo("15000");
        assertThat(ProductImportReader.parseNumber("\ufeff1,5\u00a0đ")).isEqualByComparingTo("1.5");
    }

    @Test
    void numberCellsAreRoundedToFifteenSignificantDigitsLikeExcel() throws IOException {
        assertThat(ProductImportReader.excelNumber(29160.000000000004)).isEqualByComparingTo("29160");
        assertThat(ProductImportReader.excelNumber(0.1 + 0.2)).isEqualByComparingTo("0.3");
        assertThat(ProductImportReader.excelNumber(1.5)).isEqualByComparingTo("1.5");
        assertThat(ProductImportReader.excelNumber(8934563138165.0).toPlainString()).isEqualTo("8934563138165");

        byte[] file;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Tên Sản phẩm");
            header.createCell(1).setCellValue("Giá bán");
            header.createCell(2).setCellValue("Giá vốn ban đầu");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Hàng");
            row.createCell(1).setCellValue(29160.000000000004);
            // Excel hiện 29.160 nhưng lưu 29160.000000000004
            row.createCell(2).setCellFormula("27000*1.08");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
            file = out.toByteArray();
        }
        ImportRow read = ProductImportReader.read(file).getFirst();
        assertThat(read.numbers().get(ImportColumn.SALE_PRICE)).isEqualByComparingTo("29160");
        assertThat(read.numbers().get(ImportColumn.OPENING_UNIT_COST)).isEqualByComparingTo("29160");
        assertThat(read.cellErrors()).isEmpty();
    }

    @Test
    void theFormLabelTonKhoDauKyIsAcceptedForTheOpeningQuantityColumn() throws IOException {
        assertThat(ImportColumn.ofHeader("Tồn kho đầu kỳ")).isEqualTo(ImportColumn.OPENING_QUANTITY);
        assertThat(ImportColumn.ofHeader(" tồn  KHO đầu kỳ *")).isEqualTo(ImportColumn.OPENING_QUANTITY);

        ImportRow read = ProductImportReader.read(sheet(List.of("Tên Sản phẩm", "Giá bán", "Tồn kho đầu kỳ"),
                List.of("Hàng", 1000, 5))).getFirst();
        assertThat(read.numbers().get(ImportColumn.OPENING_QUANTITY)).isEqualByComparingTo("5");

        // Có cả hai tên trong một file: cột lặp
        assertThatThrownBy(() -> ProductImportReader.read(sheet(
                List.of("Tên Sản phẩm", "Giá bán", "Tồn đầu kỳ", "Tồn kho đầu kỳ"), List.of("Hàng", 1000, 5, 6))))
                .isInstanceOfSatisfying(ProblemException.class,
                        p -> assertThat(p.type()).isEqualTo(ProductImportReader.INVALID_FILE))
                .hasMessageContaining("lặp")
                .hasMessageContaining("Tồn đầu kỳ");
    }

    @Test
    void headersMatchByNameIgnoringCaseSpacesAndTheRequiredMark() {
        assertThat(ImportColumn.ofHeader("Tên Sản phẩm*")).isEqualTo(ImportColumn.NAME);
        assertThat(ImportColumn.ofHeader("  tên   SẢN PHẨM  * ")).isEqualTo(ImportColumn.NAME);
        assertThat(ImportColumn.ofHeader("Giá bán")).isEqualTo(ImportColumn.SALE_PRICE);
        assertThat(ImportColumn.ofHeader("Giá vốn ban đầu")).isEqualTo(ImportColumn.OPENING_UNIT_COST);
        assertThat(ImportColumn.ofHeader("Gia ban")).isNull();
        assertThat(ImportColumn.ofHeader("Ghi chú")).isNull();
        for (ImportColumn column : ImportColumn.values()) {
            assertThat(ImportColumn.ofHeader(column.templateHeader())).isEqualTo(column);
        }
    }

    @Test
    void onlyZipContainersArePassedToApachePoi() {
        assertThatThrownBy(() -> ProductImportReader.checkContainer("a,b\n1,2".getBytes()))
                .isInstanceOfSatisfying(ProblemException.class,
                        p -> assertThat(p.type()).isEqualTo(ProductImportReader.INVALID_FILE));
        assertThatThrownBy(() -> ProductImportReader.checkContainer(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11,
                (byte) 0xE0}))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining(".xls");
        assertThatThrownBy(() -> ProductImportReader.checkContainer(new byte[] {'P', 'K', 3, 4, 0, 0}))
                .isInstanceOf(ProblemException.class);
    }

    @Test
    void aZipThatInflatesPastTheLimitIsRejectedBeforeApachePoiReadsIt() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = ooxmlZip(out)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (long written = 0; written <= ProductImportReader.MAX_UNZIPPED_BYTES; written += zeros.length) {
                zip.write(zeros);
            }
            zip.closeEntry();
        }
        byte[] bomb = out.toByteArray();
        assertThat(bomb.length).isLessThan(1024 * 1024);
        assertThatThrownBy(() -> ProductImportReader.checkContainer(bomb))
                .isInstanceOfSatisfying(ProblemException.class,
                        p -> assertThat(p.type()).isEqualTo(ProductImportReader.INVALID_FILE))
                .hasMessageContaining("giải nén");
    }

    @Test
    void aZipWithTooManyEntriesIsRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = ooxmlZip(out)) {
            for (int i = 0; i < ProductImportReader.MAX_ZIP_ENTRIES; i++) {
                zip.putNextEntry(new ZipEntry("xl/media/anh" + i + ".bin"));
                zip.write(1);
                zip.closeEntry();
            }
        }
        assertThatThrownBy(() -> ProductImportReader.checkContainer(out.toByteArray()))
                .isInstanceOfSatisfying(ProblemException.class,
                        p -> assertThat(p.type()).isEqualTo(ProductImportReader.INVALID_FILE));
    }

    /** ZIP có hai phần của một bảng tính OOXML ({@code [Content_Types].xml}, {@code xl/workbook.xml}). */
    private static ZipOutputStream ooxmlZip(ByteArrayOutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        for (String part : List.of("[Content_Types].xml", "xl/workbook.xml")) {
            zip.putNextEntry(new ZipEntry(part));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return zip;
    }

    /** File {@code .xlsx} một trang: dòng 1 là {@code headers}, dòng 2 là {@code values} (số hoặc chữ). */
    private static byte[] sheet(List<String> headers, List<Object> values) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            Row row = sheet.createRow(1);
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i) instanceof Number n) {
                    row.createCell(i).setCellValue(n.doubleValue());
                } else {
                    row.createCell(i).setCellValue(String.valueOf(values.get(i)));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
