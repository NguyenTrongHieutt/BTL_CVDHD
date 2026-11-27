package vn.nhom17.banhang.catalog.internal.product;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.platform.ProblemException;

final class LargeImportReader {
    private static final Semaphore EXCEL_SLOT = new Semaphore(1);
    private static final long MIB = 1024 * 1024;
    private static final int MAX_CELL = 8192;
    private static final int MAX_COLUMNS = 256;

    private LargeImportReader() {}

    static void read(InputStream input, String filename, long bytes, ProductImportReader.Profile profile,
            int maxRows, Consumer<ProductImportReader.ImportRow> rows) {
        int[] count = {0};
        Consumer<ProductImportReader.ImportRow> bounded = row -> {
            if (++count[0] > maxRows) throw ProductImportReader.invalid("File vượt " + maxRows + " dòng dữ liệu.");
            rows.accept(row);
        };
        try {
            if (filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                ProductImportReader.streamCsv(input, profile, bounded);
            } else {
                excel(input, bytes, profile, bounded);
            }
        } catch (ProblemException invalid) {
            throw invalid;
        } catch (IOException incomplete) {
            // Stream lỗi giữa chừng có thể là lỗi kho tạm thời, để Kafka retry.
            throw new java.io.UncheckedIOException(incomplete);
        }
    }

    private static void excel(InputStream input, long bytes, ProductImportReader.Profile profile,
                              Consumer<ProductImportReader.ImportRow> rows) throws IOException {
        EXCEL_SLOT.acquireUninterruptibly();
        Path temporary = null;
        try {
            temporary = Files.createTempFile("banhang-import-", ".xlsx");
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                long written = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    written += n;
                    if (written > bytes) throw ProductImportReader.invalid("Kích thước file Excel không khớp.");
                    output.write(buffer, 0, n);
                }
                if (written != bytes) throw new IOException("Object ended before expected length");
            }
            readExcelPackage(temporary, profile, rows);
        } finally {
            try {
                if (temporary != null) Files.deleteIfExists(temporary);
            } finally { EXCEL_SLOT.release(); }
        }
    }

    private static void readExcelPackage(Path path, ProductImportReader.Profile profile,
            Consumer<ProductImportReader.ImportRow> rows) {
        try {
            verifyZip(path);
            try (var pack = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
                var excel = new XSSFReader(pack);
                excel.setUseReadOnlySharedStringsTable(true);
                var sheets = excel.getSheetsData();
                if (!sheets.hasNext()) throw ProductImportReader.invalid("File không có trang tính.");
                try (var first = sheets.next()) {
                    var xml = XMLHelper.newXMLReader();
                    xml.setContentHandler(new Sheet(excel.getSharedStringsTable(), profile, rows));
                    xml.parse(new InputSource(first));
                }
            }
        } catch (ProblemException invalid) {
            throw invalid;
        } catch (org.apache.poi.openxml4j.exceptions.OpenXML4JException | org.xml.sax.SAXException
                 | javax.xml.parsers.ParserConfigurationException | IOException | IllegalArgumentException invalid) {
            throw ProductImportReader.invalid("File Excel không đọc được hoặc không đúng mẫu.");
        }
    }

    private static void verifyZip(Path path) throws IOException {
        long expanded = 0;
        int entries = 0;
        boolean workbook = false;
        byte[] buffer = new byte[16384];
        try (var zip = new ZipInputStream(Files.newInputStream(path))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 1000) throw ProductImportReader.invalid("File Excel có quá nhiều thành phần.");
                workbook |= entry.getName().equals("xl/workbook.xml");
                long entryBytes = 0;
                long entryLimit = entry.getName().equals("xl/sharedStrings.xml") ? 16 * MIB : 256 * MIB;
                for (int n; (n = zip.read(buffer)) != -1;) {
                    expanded += n;
                    entryBytes += n;
                    if (expanded > 256 * MIB || entryBytes > entryLimit) {
                        throw ProductImportReader.invalid("Dữ liệu Excel vượt giới hạn sau khi giải nén.");
                    }
                }
            }
        }
        if (!workbook) throw ProductImportReader.invalid("Chọn file Excel .xlsx đúng định dạng.");
    }

    private record Cell(String type, String value) {}

    private static final class Sheet extends DefaultHandler {
        private final SharedStrings strings;
        private final ProductImportReader.Profile profile;
        private final Consumer<ProductImportReader.ImportRow> output;
        private final List<Cell> cells = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private Map<ImportColumn, Integer> header;
        private int row;
        private int column;
        private boolean capture;
        private String type;

        Sheet(SharedStrings strings, ProductImportReader.Profile profile,
                Consumer<ProductImportReader.ImportRow> output) {
            this.strings = strings;
            this.profile = profile;
            this.output = output;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String node = localName.isEmpty() ? qName : localName;
            switch (node) {
                case "row" -> {
                    String original = attributes.getValue("r");
                    row = original == null ? row + 1 : Integer.parseInt(original);
                    cells.clear();
                    column = -1;
                }
                case "c" -> {
                    String address = attributes.getValue("r");
                    column = address == null ? column + 1 : new CellReference(address).getCol();
                    if (column < 0 || column >= MAX_COLUMNS) throw ProductImportReader.invalid("Excel vượt 256 cột.");
                    type = attributes.getValue("t");
                    text.setLength(0);
                }
                case "v" -> capture = true;
                case "t" -> capture = "inlineStr".equals(type);
                default -> { }
            }
        }

        @Override
        public void characters(char[] characters, int start, int length) {
            if (capture) {
                if (length > MAX_CELL - text.length()) throw ProductImportReader.invalid("Ô Excel quá dài.");
                text.append(characters, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String node = localName.isEmpty() ? qName : localName;
            switch (node) {
                case "v", "t" -> capture = false;
                case "c" -> {
                    String value = text.toString();
                    if ("s".equals(type) && !value.isEmpty()) {
                        value = strings.getItemAt(Integer.parseInt(value)).getString();
                        if (value.length() > MAX_CELL) throw ProductImportReader.invalid("Ô Excel quá dài.");
                    }
                    while (cells.size() <= column) cells.add(null);
                    cells.set(column, new Cell(type, value));
                }
                case "row" -> row();
                default -> { }
            }
        }

        @Override public void endDocument() {
            if (header == null) ProductImportReader.headerColumns(List.of(), profile);
        }

        private void row() {
            if (header == null) {
                header = ProductImportReader.headerColumns(row == 1
                        ? cells.stream().map(cell -> cell == null ? null : cell.value()).toList()
                        : List.of(), profile);
                return;
            }
            Map<ImportColumn, String> texts = new EnumMap<>(ImportColumn.class);
            Map<ImportColumn, BigDecimal> numbers = new EnumMap<>(ImportColumn.class);
            Map<ImportColumn, String> errors = new EnumMap<>(ImportColumn.class);
            header.forEach((field, position) -> {
                if (position >= cells.size() || cells.get(position) == null) return;
                Cell cell = cells.get(position);
                String value = cell.value();
                if (TextFields.normalize(value, true).isEmpty()) return;
                if ("e".equals(cell.type())) {
                    errors.put(field, "Ô " + field.header + " có lỗi công thức");
                    return;
                }
                boolean numeric = cell.type() == null || cell.type().equals("n");
                BigDecimal parsed = numeric ? ProductImportReader.excelNumber(Double.parseDouble(value)) : null;
                if (field.kind == ImportColumn.Kind.TEXT) {
                    texts.put(field, parsed == null ? value : parsed.toPlainString());
                } else {
                    if (!numeric) parsed = ProductImportReader.parseNumber(value);
                    if (parsed == null) {
                        texts.put(field, value);
                        errors.put(field, field.header + " phải là số");
                    } else numbers.put(field, parsed);
                }
            });
            if (!texts.isEmpty() || !numbers.isEmpty() || !errors.isEmpty()) {
                output.accept(new ProductImportReader.ImportRow(row, texts, numbers, errors));
            }
        }
    }
}
