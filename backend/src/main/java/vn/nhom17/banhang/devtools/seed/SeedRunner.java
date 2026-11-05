package vn.nhom17.banhang.devtools.seed;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.TimeZones;

/**
 * Khung chương trình nạp dữ liệu mẫu (CB-06; AD-18), chủ: Lân. Chỉ chạy ở profile {@code seed}.
 *
 * <p>GĐ0: đọc và kiểm tra các file {@code deploy/seed/store-*.json} (định dạng: {@code deploy/seed/README.md}),
 * in thống kê. SD-01 (GĐ2): gọi API công khai của platform → catalog → inventory → sales theo đúng thứ tự trong README,
 * với {@code Clock} neo vào {@code SEED_ANCHOR_DATE}, KHÔNG INSERT thẳng vào bảng; sau đó kiểm tra bất biến
 * {@code stock.quantity = Σ stock_ledger}, {@code loyalty_account.balance = Σ loyalty_ledger},
 * {@code order_counter ≥ mã Đơn hàng lớn nhất}.
 */
@Component
@Profile("seed")
class SeedRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
    static final LocalDate DEFAULT_ANCHOR = LocalDate.of(2026, 12, 16);

    private final ConfigurableApplicationContext context;
    private final JsonMapper json;
    private final Clock clock;
    private final Path dir;
    private final String anchorDate;
    private int exitCode;

    SeedRunner(ConfigurableApplicationContext context, JsonMapper json, Clock clock, @Value("${app.seed.dir}") String dir,
               @Value("${app.seed.anchor-date:}") String anchorDate) {
        this.context = context;
        this.json = json;
        this.clock = clock;
        this.dir = Path.of(dir);
        this.anchorDate = anchorDate;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        try {
            load();
        } finally {
            // Job chạy một lần rồi thoát (các luồng nền của Spring không giữ container sống)
            System.exit(SpringApplication.exit(context, this));
        }
    }

    private void load() throws IOException {
        // Để trống = ngày neo chuẩn của bộ dữ liệu, khớp eval/answers/expected.json (deploy/seed/README.md)
        LocalDate anchor = anchorDate == null || anchorDate.isBlank() ? DEFAULT_ANCHOR : LocalDate.parse(anchorDate);
        log.info("Hôm nay (giờ Việt Nam): {}", LocalDate.now(clock.withZone(TimeZones.VIETNAM)));
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "store-*.json")) {
            stream.forEach(files::add);
        }
        if (files.isEmpty()) {
            log.error("Không thấy file store-*.json trong {}", dir.toAbsolutePath());
            exitCode = 2;
            return;
        }
        log.info("Ngày neo dữ liệu mẫu: {}", anchor);
        for (Path file : files) {
            JsonNode root = json.readTree(file.toFile());
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, JsonNode> field : root.properties()) {
                JsonNode value = field.getValue();
                summary.append(field.getKey()).append('=')
                        .append(value.isArray() ? String.valueOf(value.size()) : value.getNodeType().name().toLowerCase())
                        .append(' ');
            }
            log.info("{}: {}", file.getFileName(), summary.toString().trim());
        }
        log.warn("GĐ0: mới kiểm tra định dạng. Nạp thật qua API các module làm ở SD-01 (Lân).");
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
