package vn.nhom17.banhang.sales.internal;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class VietQr {
    private VietQr() {}

    static String encode(String bin, String account, long amount, String reference) {
        String bank = field("00", bin) + field("01", account);
        String merchant = field("00", "A000000727") + field("01", bank) + field("02", "QRIBFTTA");
        String payload = "000201010212" + field("38", merchant) + "5303704"
            + field("54", Long.toString(amount)) + "5802VN" + field("62", field("08", reference)) + "6304";
        int crc = 0xffff;
        for (byte next : payload.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (next & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc << 1) ^ ((crc & 0x8000) == 0 ? 0 : 0x1021)) & 0xffff;
            }
        }
        return payload + String.format(Locale.ROOT, "%04X", crc);
    }

    private static String field(String tag, String value) {
        return tag + String.format(Locale.ROOT, "%02d", value.length()) + value;
    }
}
