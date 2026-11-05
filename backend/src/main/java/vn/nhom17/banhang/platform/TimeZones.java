package vn.nhom17.banhang.platform;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Múi giờ và cách cắt ngày (AD-8, AD-19). Mọi module lấy giờ qua {@link java.time.Clock} được inject. */
public final class TimeZones {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private TimeZones() {
    }

    /** Đầu ngày theo giờ Việt Nam. */
    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(VIETNAM).toInstant();
    }

    /** Mốc loại trừ cho khoảng ngày bao gồm cả {@code toDate}: đầu ngày hôm sau. */
    public static Instant endExclusive(LocalDate toDate) {
        return toDate.plusDays(1).atStartOfDay(VIETNAM).toInstant();
    }

    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(VIETNAM).toLocalDate();
    }
}
