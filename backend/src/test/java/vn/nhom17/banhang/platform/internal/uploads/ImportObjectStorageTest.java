package vn.nhom17.banhang.platform.internal.uploads;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ImportObjectStorageTest {
    @Test
    void signsExactLengthAndIntegrityWithoutExposingTransportHeaders() {
        String checksum = Base64.getEncoder().encodeToString(new byte[32]);
        try (var storage = new ImportObjectStorage(true, "banhang-imports", "http://localhost:9000",
                "https://upload.example.test", "us-east-1", "test-access", "test-only-secret", true)) {
            var part = storage.sign("imports/store/upload", "remote-session", 3, 6 * 1024 * 1024L, checksum,
                    Duration.ofMinutes(10));
            String decoded = URLDecoder.decode(part.url(), StandardCharsets.UTF_8);
            assertThat(decoded).startsWith("https://upload.example.test/banhang-imports/imports/store/upload?")
                    .contains("partNumber=3", "uploadId=remote-session", "X-Amz-Expires=600", "content-length");
            assertThat(part.headers()).doesNotContainKeys("host", "content-length");
            assertThat(part.headers().getOrDefault("x-amz-checksum-sha256", decoded)).contains(checksum);
        }
    }
}
