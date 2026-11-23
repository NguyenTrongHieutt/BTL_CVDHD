package vn.nhom17.banhang.catalog.internal.product;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.product.ProductImportReader.ImportRow;

/**
 * File mẫu nhập Sản phẩm (FR-6, SP-06): {@code GET /api/v1/catalog/products/import-template}.
 *
 * <ul>
 *   <li>Trang "Sản phẩm" (trang đầu, trang được đọc khi nhập): dòng 1 là tiêu đề theo {@link ImportColumn}, cột bắt
 *       buộc có dấu {@code *} và tô đậm màu; mỗi ô tiêu đề có ghi chú quy tắc của cột. Dòng 2 là một dòng ví dụ; nhập
 *       mà dòng ví dụ còn nguyên thì dòng đó được bỏ qua, báo trong {@code failed} ({@link #isExample}).</li>
 *   <li>Cột tiền, số lượng định dạng số {@code #,##0} (Excel tiếng Việt hiện {@code 15.000}); cột SKU, Mã vạch định
 *       dạng chữ, để mã vạch gõ vào không mất số 0 ở đầu hay thành {@code 8,9E+12}; cột Mô tả xuống dòng trong ô.</li>
 *   <li>Trang "Hướng dẫn": cách điền và giới hạn của một lần nhập.</li>
 * </ul>
 */
final class ProductImportTemplate {

    static final String FILENAME = "mau-nhap-san-pham.xlsx";
    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String DATA_SHEET = "Sản phẩm";
    static final String GUIDE_SHEET = "Hướng dẫn";

    /** Dòng ví dụ (dòng 2), theo thứ tự cột của mẫu. */
    static final Map<ImportColumn, Object> EXAMPLE = Map.of(
            ImportColumn.NAME, "Sữa tươi Vinamilk 1L",
            ImportColumn.CATEGORY, "Đồ uống",
            ImportColumn.UNIT, "hộp",
            ImportColumn.SALE_PRICE, 32000,
            ImportColumn.OPENING_QUANTITY, 24,
            ImportColumn.OPENING_UNIT_COST, 27000,
            ImportColumn.SKU, "VNM-1L",
            ImportColumn.BARCODE, "8934673123456",
            ImportColumn.DESCRIPTION, "Hộp giấy 1 lít");

    /** Lý do báo trong {@code failed} khi dòng ví dụ còn nguyên ({@link #isExample}). */
    static final String EXAMPLE_ROW_MESSAGE =
            "Dòng ví dụ của file mẫu, không nhập. Xóa dòng này hoặc sửa thành Sản phẩm của bạn.";

    private static final List<String> GUIDE = List.of(
            "Cách nhập Sản phẩm từ file này",
            "",
            "1. Điền mỗi Sản phẩm một dòng ở trang \"" + DATA_SHEET + "\", bắt đầu từ dòng 2. Dòng 1 là tiêu đề cột: "
                    + "không xóa, không đổi tên (được đổi thứ tự cột, bỏ cột không bắt buộc).",
            "2. Cột có dấu * là bắt buộc: Tên Sản phẩm, Giá bán. Đưa chuột vào ô tiêu đề để xem quy tắc của từng cột.",
            "3. Dòng 2 là dòng ví dụ: để nguyên thì dòng này được bỏ qua (báo trong kết quả, không nhập). Xóa đi "
                    + "hoặc sửa thành Sản phẩm của bạn.",
            "4. Tiền là số nguyên đồng (15000 hoặc 15.000), không có số lẻ. Có Tồn đầu kỳ thì phải có Giá vốn ban đầu.",
            "5. Danh mục ghi theo tên; tên chưa có thì hệ thống tạo Danh mục mới.",
            "6. SKU, Mã vạch không được trùng với Sản phẩm đã có hay với dòng khác trong file (dòng trên được nhập, "
                    + "dòng dưới báo lỗi). Để trống SKU thì hệ thống tự sinh.",
            "7. Mỗi lần nhập tối đa 1000 dòng, file .xlsx tối đa 2 MB. Dòng trống được bỏ qua.",
            "8. Dòng sai được báo kèm số dòng và lý do; các dòng đúng vẫn được nhập. Sửa các dòng sai rồi chỉ nhập "
                    + "các dòng đó: nhập lại dòng đã nhập thì dòng có SKU hoặc mã vạch bị báo trùng, còn dòng không có "
                    + "SKU, mã vạch sẽ được tạo thêm một Sản phẩm nữa.");

    private ProductImportTemplate() {
    }

    /**
     * Dòng giống hệt dòng ví dụ {@link #EXAMPLE} ở mọi cột: người dùng điền tiếp bên dưới mà quên xóa dòng ví dụ. Chữ so
     * sau khi chuẩn hóa như trường văn bản ({@link TextFields#normalize}), SKU và mã vạch không phân biệt hoa thường
     * (lưu chữ HOA); số so theo giá trị ({@code 32000} hay {@code "32.000"}). Dòng thiếu một cột, có ô đọc lỗi, hay
     * khác một ô là dòng thường.
     */
    static boolean isExample(ImportRow row) {
        if (!row.cellErrors().isEmpty()) {
            return false;
        }
        for (ImportColumn column : ImportColumn.values()) {
            String expected = String.valueOf(EXAMPLE.get(column));
            if (column.kind == ImportColumn.Kind.NUMBER) {
                BigDecimal actual = row.numbers().get(column);
                if (actual == null || actual.compareTo(new BigDecimal(expected)) != 0) {
                    return false;
                }
            } else {
                String actual = row.texts().get(column);
                if (actual == null || !exampleKey(column, actual).equals(exampleKey(column, expected))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String exampleKey(ImportColumn column, String text) {
        String normalized = TextFields.normalize(text, column == ImportColumn.DESCRIPTION);
        return column == ImportColumn.SKU || column == ImportColumn.BARCODE
                ? normalized.toUpperCase(Locale.ROOT) : normalized;
    }

    static byte[] build() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            buildDataSheet(workbook);
            buildGuideSheet(workbook);
            workbook.setActiveSheet(0);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void buildDataSheet(XSSFWorkbook workbook) {
        Sheet sheet = workbook.createSheet(DATA_SHEET);
        CreationHelper helper = workbook.getCreationHelper();
        DataFormat formats = workbook.createDataFormat();
        CellStyle header = headerStyle(workbook, false);
        CellStyle requiredHeader = headerStyle(workbook, true);
        CellStyle number = workbook.createCellStyle();
        number.setDataFormat(formats.getFormat("#,##0"));
        CellStyle text = workbook.createCellStyle();
        text.setDataFormat(formats.getFormat("@"));
        CellStyle wrapped = workbook.createCellStyle();
        wrapped.setWrapText(true);

        Drawing<?> drawing = sheet.createDrawingPatriarch();
        Row headerRow = sheet.createRow(0);
        Row exampleRow = sheet.createRow(1);
        for (ImportColumn column : ImportColumn.values()) {
            int index = column.ordinal();
            CellStyle columnStyle = switch (column) {
                case SALE_PRICE, OPENING_QUANTITY, OPENING_UNIT_COST -> number;
                case SKU, BARCODE -> text;
                case DESCRIPTION -> wrapped;
                default -> null;
            };
            if (columnStyle != null) {
                sheet.setDefaultColumnStyle(index, columnStyle);
            }
            sheet.setColumnWidth(index, columnWidth(column) * 256);

            Cell cell = headerRow.createCell(index);
            cell.setCellValue(column.templateHeader());
            cell.setCellStyle(column.required ? requiredHeader : header);
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(index);
            anchor.setCol2(index + 3);
            anchor.setRow1(0);
            anchor.setRow2(4);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(helper.createRichTextString(column.note));
            cell.setCellComment(comment);

            Cell example = exampleRow.createCell(index);
            Object value = EXAMPLE.get(column);
            if (value instanceof Number n) {
                example.setCellValue(n.doubleValue());
            } else {
                example.setCellValue(String.valueOf(value));
            }
            if (columnStyle != null) {
                example.setCellStyle(columnStyle);
            }
        }
        sheet.createFreezePane(0, 1);
    }

    private static void buildGuideSheet(XSSFWorkbook workbook) {
        Sheet sheet = workbook.createSheet(GUIDE_SHEET);
        sheet.setColumnWidth(0, 120 * 256);
        CellStyle title = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        title.setFont(bold);
        CellStyle wrapped = workbook.createCellStyle();
        wrapped.setWrapText(true);
        for (int i = 0; i < GUIDE.size(); i++) {
            Cell cell = sheet.createRow(i).createCell(0);
            cell.setCellValue(GUIDE.get(i));
            cell.setCellStyle(i == 0 ? title : wrapped);
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook, boolean required) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        if (required) {
            font.setColor(IndexedColors.DARK_RED.getIndex());
        }
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Độ rộng cột (số ký tự). */
    private static int columnWidth(ImportColumn column) {
        return switch (column) {
            case NAME -> 32;
            case DESCRIPTION -> 40;
            case CATEGORY, BARCODE -> 18;
            case OPENING_UNIT_COST -> 17;
            default -> 14;
        };
    }
}
