package vn.nhom17.banhang.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Các luật kiến trúc chạy mỗi lần build (AD-21). */
class ArchitectureRulesTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java/vn/nhom17/banhang");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    /** AD-3: bảng → module chủ (module duy nhất được INSERT/UPDATE/DELETE). */
    private static final Map<String, String> TABLE_OWNER = Map.ofEntries(
            Map.entry("store", "platform"), Map.entry("app_user", "platform"), Map.entry("audit_log", "platform"),
            Map.entry("order_counter", "platform"), Map.entry("idempotency_record", "platform"),
            Map.entry("category", "catalog"), Map.entry("product", "catalog"), Map.entry("product_variant", "catalog"),
            Map.entry("product_image", "catalog"), Map.entry("customer", "catalog"), Map.entry("supplier", "catalog"),
            Map.entry("stock", "inventory"), Map.entry("stock_ledger", "inventory"), Map.entry("lot", "inventory"),
            Map.entry("purchase_receipt", "inventory"), Map.entry("purchase_receipt_line", "inventory"),
            Map.entry("stocktake", "inventory"), Map.entry("stocktake_line", "inventory"),
            Map.entry("sales_order", "sales"), Map.entry("sales_order_line", "sales"), Map.entry("payment", "sales"),
            Map.entry("sales_return", "sales"), Map.entry("sales_return_line", "sales"),
            Map.entry("loyalty_account", "sales"), Map.entry("loyalty_ledger", "sales"));

    private static final Pattern DML =
            Pattern.compile("(?i)\\b(insert\\s+into|update|delete\\s+from)\\s+app\\.([a-z_]+)");

    @Test
    void onlyTheOwnerModuleWritesItsTables() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String module = MAIN_JAVA.relativize(file).getName(0).toString();
            Matcher m = DML.matcher(Files.readString(file));
            while (m.find()) {
                String table = m.group(2).toLowerCase();
                String owner = TABLE_OWNER.get(table);
                if (owner == null) {
                    violations.add(file + ": bảng app." + table + " chưa có trong TABLE_OWNER (thêm khi tạo bảng mới)");
                } else if (!owner.equals(module)) {
                    violations.add(file + ": module " + module + " ghi bảng app." + table + " của " + owner);
                }
            }
        }
        assertThat(violations).as("AD-3: chỉ module chủ được ghi bảng").isEmpty();
    }

    @Test
    void backendHasNoAddressOfTheAiService() throws IOException {
        Pattern url = Pattern.compile("(?i)(https?://[^\\s\"']*ai-service|ai-service:\\d+|localhost:8000)");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Stream.concat(Files.walk(MAIN_JAVA), Files.walk(MAIN_RESOURCES))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (url.matcher(Files.readString(file)).find()) {
                    violations.add(file.toString());
                }
            }
        }
        assertThat(violations).as("AD-2: backend không có cấu hình hay URL trỏ tới ai-service").isEmpty();
    }

    @Test
    void timeComesFromTheInjectedClock() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.nhom17.banhang");
        noClasses().should().callMethod(Instant.class, "now")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(OffsetDateTime.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .because("AD-8: mọi module lấy giờ qua java.time.Clock được inject")
                .check(classes);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
