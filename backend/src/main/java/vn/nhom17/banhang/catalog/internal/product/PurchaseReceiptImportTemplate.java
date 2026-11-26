package vn.nhom17.banhang.catalog.internal.product;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.Template;

final class PurchaseReceiptImportTemplate {
    private static final List<String> GUIDE = List.of(
            "Nhập kho từ CSV hoặc Excel",
            "Mỗi dòng có SKU, Số lượng và Giá nhập bắt buộc. Số lượng, Giá nhập là số nguyên lớn hơn 0.",
            "SKU đã có: dùng tên, đơn vị và giá bán trong danh mục. Không thay đổi thông tin hàng đã có.",
            "SKU mới: cần Tên Sản phẩm và Giá bán. Danh mục chưa có được tạo khi lưu phiếu.",
            "Cùng Mã sản phẩm cha trong file: nhóm Biến thể. Mỗi Biến thể cần tên hoặc cặp thuộc tính.",
            "Nhóm có SKU đã có sẽ thêm Biến thể mới vào Sản phẩm đó. Nhóm không được chứa SKU của hai Sản phẩm.",
            "Nếu Sản phẩm đang có một Biến thể, điền Tên Biến thể cho dòng SKU hiện có để chuyển thành nhiều Biến thể.",
            "Không có Mã sản phẩm cha: mỗi SKU mới là một Sản phẩm một Biến thể.",
            "Toàn bộ số lượng được nhận trên một Phiếu nhập. Không nhập Tồn đầu kỳ hay Giá vốn ban đầu.",
            "Xem trước, sửa hết lỗi rồi xác nhận. Có bất kỳ lỗi nào thì không dòng nào được lưu.",
            "Tối đa 1000 dòng, file 2 MB. CSV UTF-8; dấu phẩy hoặc chấm phẩy. Tiền có thể viết 15000 hoặc 15.000.");

    private PurchaseReceiptImportTemplate() {}

    static Template build(String format) {
        if ("csv".equalsIgnoreCase(format)) {
            String headers = String.join(",", ImportColumn.receiptColumns().stream()
                    .map(PurchaseReceiptImportTemplate::header).map(s -> "\"" + s + "\"").toList());
            return new Template(("\ufeff" + headers + "\r\n").getBytes(StandardCharsets.UTF_8),
                    "mau-nhap-kho.csv", "text/csv;charset=UTF-8");
        }
        if (!"xlsx".equalsIgnoreCase(format)) {
            throw ProductImportReader.invalid("Chọn định dạng file mẫu .xlsx hoặc .csv.");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Nhập kho");
            Row row = sheet.createRow(0);
            CellStyle title = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            title.setFont(font);
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            CellStyle number = workbook.createCellStyle();
            number.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            for (int i = 0; i < ImportColumn.receiptColumns().size(); i++) {
                ImportColumn column = ImportColumn.receiptColumns().get(i);
                row.createCell(i).setCellValue(header(column));
                row.getCell(i).setCellStyle(title);
                sheet.setColumnWidth(i, (column == ImportColumn.NAME ? 32 : 22) * 256);
                sheet.setDefaultColumnStyle(i, column.kind == ImportColumn.Kind.NUMBER ? number : text);
            }
            sheet.createFreezePane(0, 1);
            Sheet guide = workbook.createSheet("Hướng dẫn");
            guide.setColumnWidth(0, 130 * 256);
            for (int i = 0; i < GUIDE.size(); i++) {
                guide.createRow(i).createCell(0).setCellValue(GUIDE.get(i));
            }
            workbook.write(out);
            return new Template(out.toByteArray(), "mau-nhap-kho.xlsx", ProductImportTemplate.CONTENT_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String header(ImportColumn column) {
        return column.header + (ProductImportReader.Profile.RECEIPT.required(column) ? "*" : "");
    }
}
