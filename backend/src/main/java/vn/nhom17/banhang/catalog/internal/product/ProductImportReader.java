package vn.nhom17.banhang.catalog.internal.product;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Đọc file nhập Sản phẩm (FR-6, SP-06) thành các dòng thô; chưa kiểm quy tắc trường (việc của
 * {@link ProductService#check}), chưa đụng database.
 *
 * <ul>
 *   <li><b>Tệp:</b> {@code .csv} đọc UTF-8, nhận BOM, dấu phẩy hoặc chấm phẩy, ô có ngoặc kép và xuống dòng.
 *       Với XLSX, kiểm byte đầu là ZIP ({@code PK\3\4}; {@code .xls}, file có mật khẩu là OLE2 bị từ chối),
 *       rồi kiểm gói ZIP có {@code [Content_Types].xml} và
 *       {@code xl/workbook.xml} và giải nén không quá {@value #MAX_UNZIPPED_BYTES} byte (chặn file nén kiểu "bom" trước
 *       khi Apache POI dựng cả file trong bộ nhớ). Sai: {@code 400 /errors/catalog/import-invalid-file}.</li>
 *   <li><b>Trang tính:</b> trang đầu tiên. Dòng 1 là tiêu đề, đọc theo tên ({@link ImportColumn#ofHeader}); thiếu cột
 *       bắt buộc hoặc một cột xuất hiện hai lần: {@code 400 import-invalid-file} nêu tên cột.</li>
 *   <li><b>Dòng:</b> dữ liệu từ dòng 2; dòng mà mọi ô của các cột nhận ra đều trống (hoặc chỉ có khoảng trắng) bị bỏ
 *       qua. Quá {@value #MAX_ROWS} dòng dữ liệu: {@code 400 import-invalid-file}. Số dòng báo lỗi là số dòng của
 *       Excel hoặc dòng vật lý bắt đầu bản ghi CSV (dòng 1 là tiêu đề).</li>
 *   <li><b>Ô chữ:</b> lấy chuỗi như đã nhập; ô số trong cột chữ (mã vạch gõ thành số) đổi thành chữ số, không có dạng
 *       {@code 8.9E12}.</li>
 *   <li><b>Tiêu đề:</b> ngoài tên của file mẫu còn nhận tên khác của cùng cột ({@link ImportColumn#aliases}, ví dụ
 *       "Tồn kho đầu kỳ"); có cả hai tên trong một file là cột lặp.</li>
 *   <li><b>Ô số</b> (tiền, số lượng): ô số lấy giá trị làm tròn tới 15 chữ số có nghĩa như Excel ({@code 15000};
 *       {@code 1,5} hiện trong Excel là {@code 1.5}; {@link #excelNumber}); ô chữ theo cách viết Việt Nam
 *       ({@link #parseNumber}): dấu chấm ngăn cách hàng nghìn ({@code "15.000"}), dấu phẩy là dấu thập phân
 *       ({@code "1,5"}), được có ký hiệu tiền ở cuối ({@code "15.000đ"}). Giá trị lẻ, âm, quá lớn không bị cắt mà để
 *       {@link ProductService#check} báo lỗi; chữ không đọc được thành số thì báo lỗi ngay ở ô đó.</li>
 *   <li><b>Công thức:</b> lấy kết quả Excel đã tính lưu trong file; ô lỗi ({@code #N/A}…) báo lỗi ở ô đó.</li>
 * </ul>
 */
final class ProductImportReader {

    private static final Logger log = LoggerFactory.getLogger(ProductImportReader.class);

    static final String INVALID_FILE = "/errors/catalog/import-invalid-file";
    /** Số dòng dữ liệu tối đa (không tính dòng tiêu đề, dòng trống). */
    static final int MAX_ROWS = 1000;
    /** Tổng số byte giải nén tối đa của gói ZIP; file 1000 dòng thật chỉ cỡ vài trăm KB. */
    static final long MAX_UNZIPPED_BYTES = 20L * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 1000;

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};
    /**
     * Số viết kiểu Việt Nam: dấu trừ (để báo "không được âm"), phần nguyên là chữ số liền hoặc nhóm ba chữ số cách
     * nhau bằng dấu chấm, phần lẻ sau dấu phẩy.
     */
    private static final Pattern VIETNAMESE_NUMBER = Pattern.compile("(-?)(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,(\\d+))?");
    /** Một ký hiệu tiền ở cuối: đ, ₫ (U+20AB), VND, VNĐ; không phân biệt hoa thường, có thể có khoảng trắng trước. */
    private static final Pattern CURRENCY_SUFFIX = Pattern.compile("\\s*(?:\u0111|\u20ab|vn\u0111|vnd)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Excel giữ 15 chữ số có nghĩa. */
    private static final MathContext EXCEL_PRECISION = new MathContext(15);

    private ProductImportReader() {
    }

    enum Profile {
        CATALOG, RECEIPT;

        boolean includes(ImportColumn column) {
            return (this == CATALOG ? ImportColumn.catalogColumns() : ImportColumn.receiptColumns()).contains(column);
        }

        boolean required(ImportColumn column) {
            return this == CATALOG ? column.required
                    : column == ImportColumn.SKU || column == ImportColumn.RECEIPT_QUANTITY
                    || column == ImportColumn.RECEIPT_UNIT_COST;
        }
    }

    /**
     * Một dòng dữ liệu. {@code texts}: ô chữ (chưa chuẩn hóa), và chữ gốc của ô số không đọc được để sửa khi xem
     * trước; {@code numbers}: giá trị ô của cột số;
     * ô trống không có trong hai bảng. {@code cellErrors}: lỗi đọc ô (chữ không phải số, ô lỗi công thức) theo cột.
     */
    record ImportRow(int row, Map<ImportColumn, String> texts, Map<ImportColumn, BigDecimal> numbers,
                     Map<ImportColumn, String> cellErrors) {
    }

    /** CSV phải được chọn với đuôi .csv; XLSX vẫn kiểm nội dung như phiên bản trước. */
    static List<ImportRow> read(byte[] content, String filename) {
        return read(content, filename, Profile.CATALOG);
    }

    static List<ImportRow> read(byte[] content, String filename, Profile profile) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? readCsv(content, profile) : read(content, profile);
    }

    record CsvRecord(int row, List<String> cells) {
    }

    /** UTF-8, có thể có BOM; số dòng là dòng vật lý bắt đầu bản ghi, kể cả ô chứa xuống dòng. */
    static List<ImportRow> readCsv(byte[] content) {
        return readCsv(content, Profile.CATALOG);
    }

    private static List<ImportRow> readCsv(byte[] content, Profile profile) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw invalid("CSV phải dùng mã hóa UTF-8. Hãy lưu lại dạng CSV UTF-8 rồi thử lại.");
        }
        if (text.startsWith("\ufeff")) {
            text = text.substring(1);
        }
        CsvRows records = new CsvRows(profile);
        try {
            csvRecords(new PushbackReader(new StringReader(text)), csvDelimiter(text), false, records::accept);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (records.columns == null) {
            headerColumns(List.of(), profile);
        }
        return List.copyOf(records.rows);
    }

    /** Xử lý từng bản ghi ngay; hàng triệu dòng trống trong 2 MB không tạo hàng triệu object chờ đọc. */
    private static final class CsvRows {
        private final Profile profile;
        private Map<ImportColumn, Integer> columns;
        private final List<ImportRow> rows = new ArrayList<>();

        private CsvRows(Profile profile) {
            this.profile = profile;
        }

        private void accept(CsvRecord record) {
            if (columns == null) {
                columns = headerColumns(record.cells(), profile);
                return;
            }
            ImportRow row = readCsvRow(record, columns);
            if (row != null) {
                if (rows.size() == MAX_ROWS) {
                    throw invalid("File có hơn " + MAX_ROWS + " dòng dữ liệu. Hãy tách thành nhiều file.");
                }
                rows.add(row);
            }
        }
    }

    static ImportRow readCsvRow(CsvRecord record, Map<ImportColumn, Integer> columns) {
        Map<ImportColumn, String> texts = new EnumMap<>(ImportColumn.class);
        Map<ImportColumn, BigDecimal> numbers = new EnumMap<>(ImportColumn.class);
        Map<ImportColumn, String> errors = new EnumMap<>(ImportColumn.class);
        for (Map.Entry<ImportColumn, Integer> entry : columns.entrySet()) {
            if (entry.getValue() >= record.cells().size()) {
                continue;
            }
            ImportColumn column = entry.getKey();
            String value = record.cells().get(entry.getValue());
            if (TextFields.normalize(value, true).isEmpty()) {
                continue;
            }
            if (column.kind == ImportColumn.Kind.TEXT) {
                texts.put(column, value);
            } else {
                BigDecimal number = parseNumber(value);
                if (number == null) {
                    texts.put(column, value);
                    errors.put(column, numberProblem(column, value));
                } else {
                    numbers.put(column, number);
                }
            }
        }
        if (!texts.isEmpty() || !numbers.isEmpty() || !errors.isEmpty()) {
            return new ImportRow(record.row(), texts, numbers, errors);
        }
        return null;
    }

    /** Chỉ xét dấu phân cách ở tiêu đề, ngoài dấu ngoặc kép; nhận cả CSV xuất từ Excel dùng dấu chấm phẩy. */
    private static char csvDelimiter(String text) {
        boolean quoted = false;
        int commas = 0;
        int semicolons = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted) {
                if (c == '\r' || c == '\n') {
                    break;
                }
                commas += c == ',' ? 1 : 0;
                semicolons += c == ';' ? 1 : 0;
            }
        }
        return semicolons > commas ? ';' : ',';
    }

    static void streamCsv(InputStream input, Profile profile, Consumer<ImportRow> consumer) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            reader.mark(8194);
            StringBuilder first = new StringBuilder();
            for (int value; (value = reader.read()) != -1 && value != '\r' && value != '\n'; ) {
                if (first.length() == 8192) throw invalid("Dòng tiêu đề CSV quá dài.");
                first.append((char) value);
            }
            reader.reset();
            if (!first.isEmpty() && first.charAt(0) == '\ufeff') reader.read();
            class Records implements Consumer<CsvRecord> {
                Map<ImportColumn, Integer> columns;
                @Override public void accept(CsvRecord record) {
                    if (columns == null) columns = headerColumns(record.cells(), profile);
                    else {
                        var row = readCsvRow(record, columns);
                        if (row != null) consumer.accept(row);
                    }
                }
            }
            var records = new Records();
            csvRecords(new PushbackReader(reader), csvDelimiter(first.toString()), true, records);
            if (records.columns == null) headerColumns(List.of(), profile);
        } catch (CharacterCodingException invalidUtf8) {
            throw invalid("CSV phải dùng mã hóa UTF-8.");
        }
    }

    private static boolean consume(PushbackReader input, char lookahead) throws IOException {
        int next = input.read();
        if (next == lookahead) return true;
        if (next != -1) input.unread(next);
        return false;
    }

    private static void csvRecords(PushbackReader input, char delimiter, boolean bounded, Consumer<CsvRecord> consumer)
            throws IOException {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        boolean started = false;
        int line = 1;
        int recordLine = 1;
        for (int value; (value = input.read()) != -1; ) {
            char c = (char) value;
            if (bounded && (cell.length() > 8192 || cells.size() > 256)) {
                throw invalid("CSV dòng " + recordLine + " có ô quá dài hoặc quá nhiều cột.");
            }
            if (quoted) {
                if (c == '"') {
                    if (consume(input, '"')) {
                        cell.append('"');
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r') consume(input, '\n');
                    cell.append('\n');
                    line++;
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == delimiter || c == '\r' || c == '\n') {
                cells.add(cell.toString());
                cell.setLength(0);
                closedQuote = false;
                started = false;
                if (c != delimiter) {
                    consumer.accept(new CsvRecord(recordLine, List.copyOf(cells)));
                    cells.clear();
                    if (c == '\r') consume(input, '\n');
                    recordLine = ++line;
                }
            } else if (c == '"' && !started && !closedQuote) {
                quoted = true;
                started = true;
            } else {
                if (c == '"' || closedQuote && c != ' ' && c != '\t') {
                    throw invalid("CSV dòng " + recordLine + ": dấu ngoặc kép không hợp lệ.");
                }
                if (!closedQuote) {
                    cell.append(c);
                    started = true;
                }
            }
        }
        if (quoted) {
            throw invalid("CSV dòng " + recordLine + ": ô có dấu ngoặc kép chưa đóng.");
        }
        if (started || closedQuote || !cells.isEmpty() || !cell.isEmpty()) {
            cells.add(cell.toString());
            consumer.accept(new CsvRecord(recordLine, List.copyOf(cells)));
        }
    }

    /** Đọc {@code content} (đã kiểm cỡ). Tệp không đọc được, sai mẫu, quá nhiều dòng: {@code 400 import-invalid-file}. */
    static List<ImportRow> read(byte[] content) {
        return read(content, Profile.CATALOG);
    }

    private static List<ImportRow> read(byte[] content, Profile profile) {
        checkContainer(content);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw invalid("File không có trang tính nào.");
            }
            return readSheet(workbook.getSheetAt(0), profile);
        } catch (ProblemException problem) {
            throw problem;
        } catch (IOException | RuntimeException unreadable) {
            log.warn("Không đọc được file Excel nhập Sản phẩm", unreadable);
            throw unreadable();
        }
    }

    /** Byte đầu là ZIP, gói có phần của một bảng tính OOXML, giải nén không quá giới hạn. */
    static void checkContainer(byte[] content) {
        if (startsWith(content, OLE2_MAGIC)) {
            throw invalid("Chỉ nhận file Excel .xlsx. File này là .xls (Excel 97–2003) hoặc file có đặt mật khẩu: "
                    + "hãy mở bằng Excel, bỏ mật khẩu (nếu có) rồi lưu lại dạng .xlsx.");
        }
        if (!startsWith(content, ZIP_MAGIC)) {
            throw invalid("Chỉ nhận file .xlsx hoặc CSV UTF-8 có đuôi .csv. Hãy tải file mẫu, điền dữ liệu rồi chọn "
                    + "lại file.");
        }
        boolean contentTypes = false;
        boolean workbook = false;
        long unzipped = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw unreadable();
                }
                contentTypes |= "[Content_Types].xml".equals(entry.getName());
                workbook |= "xl/workbook.xml".equals(entry.getName());
                for (int n = zip.read(buffer); n > 0; n = zip.read(buffer)) {
                    unzipped += n;
                    if (unzipped > MAX_UNZIPPED_BYTES) {
                        throw invalid("File quá lớn sau khi giải nén. Hãy dùng file mẫu và chỉ giữ dữ liệu Sản phẩm.");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException broken) {
            log.warn("File nhập Sản phẩm không phải gói ZIP hợp lệ: {}", broken.toString());
            throw unreadable();
        }
        if (!contentTypes || !workbook) {
            throw unreadable();
        }
    }

    private static List<ImportRow> readSheet(Sheet sheet, Profile profile) {
        Map<ImportColumn, Integer> columns = headerColumns(sheet.getRow(0), profile);
        List<ImportRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            if (row.getRowNum() == 0) {
                continue;
            }
            ImportRow read = readRow(row, columns);
            if (read == null) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw invalid("File có hơn " + MAX_ROWS + " dòng dữ liệu. Mỗi lần nhập tối đa " + MAX_ROWS
                        + " Sản phẩm: hãy tách thành nhiều file.");
            }
            rows.add(read);
        }
        return rows;
    }

    /** Vị trí cột của từng tiêu đề nhận ra ở dòng 1; thiếu cột bắt buộc, cột lặp: {@code 400}. */
    private static Map<ImportColumn, Integer> headerColumns(Row header, Profile profile) {
        List<String> cells = new ArrayList<>();
        if (header != null) {
            for (int i = 0; i < header.getLastCellNum(); i++) {
                cells.add(cellText(header.getCell(i)));
            }
        }
        return headerColumns(cells, profile);
    }

    static Map<ImportColumn, Integer> headerColumns(List<String> header, Profile profile) {
        Map<ImportColumn, Integer> columns = new EnumMap<>(ImportColumn.class);
        Set<String> repeated = new LinkedHashSet<>();
        if (header != null) {
            for (int i = 0; i < header.size(); i++) {
                String text = header.get(i);
                ImportColumn column = text == null ? null : ImportColumn.ofHeader(text);
                if (column != null && profile.includes(column) && columns.putIfAbsent(column, i) != null) {
                    repeated.add(column.header);
                }
            }
        }
        if (!repeated.isEmpty()) {
            throw invalid("Dòng tiêu đề có cột lặp lại: " + String.join(", ", repeated)
                    + ". Mỗi cột chỉ được có một lần.");
        }
        List<String> missing = new ArrayList<>();
        for (ImportColumn column : ImportColumn.values()) {
            if (profile.required(column) && !columns.containsKey(column)) {
                missing.add(column.header);
            }
        }
        if (!missing.isEmpty()) {
            throw invalid("File thiếu cột bắt buộc: " + String.join(", ", missing) + ". Dòng 1 phải là dòng tiêu đề "
                    + "như file mẫu (dữ liệu từ dòng 2).");
        }
        return columns;
    }

    /** Dòng dữ liệu, hoặc {@code null} khi mọi ô của các cột nhận ra đều trống. */
    private static ImportRow readRow(Row row, Map<ImportColumn, Integer> columns) {
        Map<ImportColumn, String> texts = new EnumMap<>(ImportColumn.class);
        Map<ImportColumn, BigDecimal> numbers = new EnumMap<>(ImportColumn.class);
        Map<ImportColumn, String> errors = new EnumMap<>(ImportColumn.class);
        for (Map.Entry<ImportColumn, Integer> entry : columns.entrySet()) {
            ImportColumn column = entry.getKey();
            Cell cell = row.getCell(entry.getValue());
            CellType type = cell == null ? CellType.BLANK : valueType(cell);
            if (type == CellType.ERROR) {
                errors.put(column, "Ô " + column.header + " có lỗi công thức");
                continue;
            }
            if (column.kind == ImportColumn.Kind.TEXT) {
                String text = cellText(cell);
                if (text != null) {
                    texts.put(column, text);
                }
                continue;
            }
            if (type == CellType.NUMERIC) {
                numbers.put(column, excelNumber(cell.getNumericCellValue()));
                continue;
            }
            String text = cellText(cell);
            if (text == null) {
                continue;
            }
            BigDecimal number = parseNumber(text);
            if (number == null) {
                texts.put(column, text);
                errors.put(column, numberProblem(column, text));
            } else {
                numbers.put(column, number);
            }
        }
        if (texts.isEmpty() && numbers.isEmpty() && errors.isEmpty()) {
            return null;
        }
        return new ImportRow(row.getRowNum() + 1, texts, numbers, errors);
    }

    /**
     * Giá trị ô số như Excel hiện: làm tròn tới 15 chữ số có nghĩa (độ chính xác Excel dùng), nên phần dư nhị phân của
     * phép tính ({@code =27000*1.08} lưu 29160.000000000004) không thành "số lẻ".
     */
    static BigDecimal excelNumber(double value) {
        return new BigDecimal(value).round(EXCEL_PRECISION).stripTrailingZeros();
    }

    /**
     * Số viết bằng chữ theo cách Việt Nam: chuẩn hóa như trường văn bản ({@link TextFields#normalize}: bỏ ký tự định
     * dạng vô hình dán kèm), bỏ một ký hiệu tiền ở cuối ({@code đ}, {@code ₫}, {@code VND}, {@code VNĐ}, không phân
     * biệt hoa thường), bỏ mọi khoảng trắng; {@code "15000"}, {@code "15.000"}, {@code "15.000 đ"} là 15000;
     * {@code "1,5"} là 1.5 (để báo "phải là số nguyên"); {@code "-5"} là -5 (để báo "không được âm"). Không phải số:
     * {@code null}. Phần lẻ đúng ba chữ số ({@code "15,000"}) là {@code null}: có thể là cách viết hàng nghìn kiểu
     * Anh, không đoán.
     */
    static BigDecimal parseNumber(String text) {
        String withoutCurrency = CURRENCY_SUFFIX.matcher(TextFields.normalize(text, false)).replaceFirst("");
        String compact = withoutCurrency.codePoints()
                .filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        Matcher m = VIETNAMESE_NUMBER.matcher(compact);
        if (!m.matches() || (m.group(3) != null && m.group(3).length() == 3)) {
            return null;
        }
        String fraction = m.group(3) == null ? "" : "." + m.group(3);
        return new BigDecimal(m.group(1) + m.group(2).replace(".", "") + fraction);
    }

    private static String numberProblem(ImportColumn column, String text) {
        if (text.contains(",") && parseNumber(text.replace(",", "")) != null) {
            return column.header + " viết dấu chấm để ngăn cách hàng nghìn (ví dụ 15.000); dấu phẩy là dấu thập phân";
        }
        return column.header + " phải là số, ví dụ 15000 hoặc 15.000";
    }

    /** Kiểu giá trị của ô; ô công thức là kiểu kết quả Excel đã tính lưu trong file. */
    private static CellType valueType(Cell cell) {
        CellType type = cell.getCellType();
        return type == CellType.FORMULA ? cell.getCachedFormulaResultType() : type;
    }

    /**
     * Ô dưới dạng chữ; ô trống, ô lỗi, ô chỉ có khoảng trắng hay ký tự vô hình (trống sau khi chuẩn hóa như trường văn
     * bản): {@code null}.
     */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        String text = switch (valueType(cell)) {
            case STRING -> cell.getStringCellValue();
            // Như Excel hiện, không có dạng 8.9E12: 8935049501234.0 → "8935049501234"
            case NUMERIC -> excelNumber(cell.getNumericCellValue()).toPlainString();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            default -> null;
        };
        return text == null || TextFields.normalize(text, true).isEmpty() ? null : text;
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    static ProblemException invalid(String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, INVALID_FILE, "File nhập không hợp lệ", detail);
    }

    private static ProblemException unreadable() {
        return invalid("Không đọc được file. File phải là file Excel .xlsx (ví dụ file mẫu tải từ màn Sản phẩm), "
                + "không bị hỏng.");
    }
}
