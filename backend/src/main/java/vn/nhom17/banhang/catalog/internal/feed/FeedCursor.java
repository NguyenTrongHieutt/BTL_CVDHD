package vn.nhom17.banhang.catalog.internal.feed;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Con trỏ change-feed (AD-12): vị trí {@code (changed_at, product_id)} của phần tử cuối đã đọc; trang sau bắt đầu ở
 * {@code (changed_at, product_id) > (changedAt, productId)}.
 *
 * <p>Dạng gửi cho dịch vụ AI là chuỗi mờ: base64url không padding của {@code "<changedAt>|<productId>"}, trong đó
 * {@code changedAt} là ISO-8601 UTC đủ 6 chữ số micro giây (độ chính xác của {@code timestamptz}, ví dụ
 * {@code 2026-11-10T03:04:05.000120Z}) và {@code productId} là UUID chữ thường. Đọc chặt: năm ngoài 0001–9999, hoặc
 * chuỗi nào mã hóa lại không ra đúng nó (có padding, thiếu micro giây, UUID chữ hoa, ngày không có thật…) là hỏng.
 */
record FeedCursor(Instant changedAt, UUID productId) {

    private static final DateTimeFormatter CHANGED_AT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    /** Con trỏ thật dài 86 ký tự; chuỗi dài hơn hẳn thì khỏi giải mã. */
    private static final int MAX_LENGTH = 128;
    private static final int MIN_YEAR = 1;
    private static final int MAX_YEAR = 9999;

    FeedCursor {
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(productId, "productId");
        changedAt = changedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Vị trí của một dòng feed. */
    static FeedCursor of(ProductChange change) {
        return new FeedCursor(change.changedAt(), change.productId());
    }

    String encode() {
        String raw = CHANGED_AT.format(changedAt) + "|" + productId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Đọc con trỏ do {@link #encode()} sinh ra.
     *
     * @throws IllegalArgumentException chuỗi không phải con trỏ hợp lệ
     */
    static FeedCursor decode(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            throw invalid(null);
        }
        String raw;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw invalid(e);
        }
        int bar = raw.indexOf('|');
        if (bar < 0) {
            throw invalid(null);
        }
        FeedCursor cursor;
        try {
            Instant changedAt = CHANGED_AT.parse(raw.substring(0, bar), Instant::from);
            cursor = new FeedCursor(changedAt, UUID.fromString(raw.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalid(e);
        }
        // Năm có dấu hay quá lớn ("+300000-…") vẫn mã hóa lại được nhưng PostgreSQL không nhận
        int year = cursor.changedAt().atOffset(ZoneOffset.UTC).getYear();
        if (year < MIN_YEAR || year > MAX_YEAR || !cursor.encode().equals(text)) {
            throw invalid(null);
        }
        return cursor;
    }

    private static IllegalArgumentException invalid(Exception cause) {
        return new IllegalArgumentException("Con trỏ change-feed không hợp lệ", cause);
    }
}
