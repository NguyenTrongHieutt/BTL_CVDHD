package vn.nhom17.banhang.catalog.internal.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Định dạng con trỏ change-feed theo hợp đồng 3.2: base64url không padding của "changedAt đủ micro giây|productId". */
class FeedCursorTest {

    static final UUID ID = UUID.fromString("0b9d7a3e-5f0c-4c1e-9a5e-2f7d1c3b4a50");

    @Test
    void encodesChangedAtWithSixFractionDigitsAndRoundTrips() {
        FeedCursor whole = new FeedCursor(Instant.parse("2026-11-10T03:04:05Z"), ID);
        assertThat(raw(whole.encode())).isEqualTo("2026-11-10T03:04:05.000000Z|" + ID);
        assertThat(whole.encode()).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(FeedCursor.decode(whole.encode())).isEqualTo(whole);

        FeedCursor micros = new FeedCursor(Instant.parse("2026-11-10T03:04:05.000120Z"), ID);
        assertThat(raw(micros.encode())).isEqualTo("2026-11-10T03:04:05.000120Z|" + ID);
        assertThat(FeedCursor.decode(micros.encode())).isEqualTo(micros);
    }

    @Test
    void nanosecondsAreCutToTheMicrosecondsPostgresKeeps() {
        FeedCursor cursor = new FeedCursor(Instant.parse("2026-11-10T03:04:05.123456789Z"), ID);
        assertThat(cursor.changedAt()).isEqualTo(Instant.parse("2026-11-10T03:04:05.123456Z"));
        assertThat(raw(cursor.encode())).startsWith("2026-11-10T03:04:05.123456Z|");
    }

    @Test
    void anythingEncodeWouldNotProduceIsRejected() {
        String valid = new FeedCursor(Instant.parse("2026-11-10T03:04:05Z"), ID).encode();
        assertThatThrownBy(() -> FeedCursor.decode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode(valid + "==")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode(valid.repeat(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode(encode("2026-11-10T03:04:05.000Z|" + ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode(encode("2026-11-10T03:04:05.000000+00:00|" + ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode(encode("2026-11-10T03:04:05.000000Z|" + ID + "|x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCursorWithoutTheSeparatorIsRejectedWithoutAWrappedCause() {
        assertThatThrownBy(() -> FeedCursor.decode(encode("2026-11-10T03:04:05.000000Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause();
    }

    @Test
    void yearsOutsideOneToNineThousandNineHundredNinetyNineAreRejected() {
        // Mã hóa lại đúng y nguyên, nhưng PostgreSQL không nhận năm có dấu hay quá lớn
        FeedCursor huge = new FeedCursor(Instant.parse("+300000-01-01T00:00:00Z"), ID);
        assertThat(raw(huge.encode())).startsWith("+300000-01-01T00:00:00.000000Z|");
        assertThatThrownBy(() -> FeedCursor.decode(huge.encode())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode(encode("0000-01-01T00:00:00.000000Z|" + ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCursor.decode(encode("+10000-01-01T00:00:00.000000Z|" + ID)))
                .isInstanceOf(IllegalArgumentException.class);

        FeedCursor first = new FeedCursor(Instant.parse("0001-01-01T00:00:00Z"), ID);
        FeedCursor last = new FeedCursor(Instant.parse("9999-12-31T23:59:59.999999Z"), ID);
        assertThat(FeedCursor.decode(first.encode())).isEqualTo(first);
        assertThat(FeedCursor.decode(last.encode())).isEqualTo(last);
    }

    static String raw(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
