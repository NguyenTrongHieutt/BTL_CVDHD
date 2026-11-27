package vn.nhom17.banhang.catalog.internal.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import vn.nhom17.banhang.platform.ProblemException;

class LargeImportReaderTest {
    @Test
    void streamsCsvWithBOMSemicolonAndMultilineCells() {
        byte[] bytes = ("\ufeffTên Sản phẩm;Giá bán;SKU\r\n\"Trà\r\nsen\";15.000;TRA-01\r\n"
                + "Cà phê;30000;CA-PHE-01").getBytes(StandardCharsets.UTF_8);
        var rows = new ArrayList<ProductImportReader.ImportRow>();
        LargeImportReader.read(new ByteArrayInputStream(bytes), "nhap.csv", bytes.length,
                ProductImportReader.Profile.CATALOG, 2, rows::add);
        assertThat(rows).extracting(ProductImportReader.ImportRow::row).containsExactly(2, 4);
        assertThat(rows.getFirst().texts().get(ImportColumn.NAME)).isEqualTo("Trà\nsen");
        assertThat(rows.getFirst().numbers().get(ImportColumn.SALE_PRICE)).isEqualByComparingTo(new BigDecimal("15000"));
        assertThatThrownBy(() -> LargeImportReader.read(new ByteArrayInputStream(bytes), "nhap.csv", bytes.length,
                ProductImportReader.Profile.CATALOG, 1, row -> { })).isInstanceOf(ProblemException.class);
    }

    @Test
    void readsExcelFirstSheetAndCachedNumericFormula() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var buffer = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Sản phẩm");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Tên Sản phẩm");
            header.createCell(1).setCellValue("Giá bán");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Trà");
            row.createCell(1).setCellFormula("15000*2");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.createSheet("Hướng dẫn");
            book.write(buffer);
            bytes = buffer.toByteArray();
        }
        var rows = new ArrayList<ProductImportReader.ImportRow>();
        LargeImportReader.read(new ByteArrayInputStream(bytes), "nhap.xlsx", bytes.length,
                ProductImportReader.Profile.CATALOG, 1000, rows::add);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().numbers().get(ImportColumn.SALE_PRICE)).isEqualByComparingTo("30000");
    }
}
