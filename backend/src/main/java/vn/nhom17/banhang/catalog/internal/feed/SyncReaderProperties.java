package vn.nhom17.banhang.catalog.internal.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kết nối của datasource thứ hai, chỉ change-feed dùng (AD-5 mục 6, AD-12): role {@code sync_reader} ({@code BYPASSRLS},
 * chỉ SELECT), cùng URL với datasource chính, mật khẩu {@code DB_SYNC_PASSWORD}. Chỉ đọc, cỡ pool và thời gian chờ cố
 * định trong {@link SyncReaderJdbc}, không cấu hình được. Thiếu giá trị nào thì dừng ngay lúc khởi động.
 *
 * @param url      JDBC URL, mặc định là {@code spring.datasource.url}
 * @param username {@code sync_reader}
 * @param password {@code DB_SYNC_PASSWORD}
 */
@ConfigurationProperties("app.catalog.sync-datasource")
public record SyncReaderProperties(String url, String username, String password) {

    public SyncReaderProperties {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Thiếu app.catalog.sync-datasource.url (datasource sync_reader, AD-12)");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("Thiếu app.catalog.sync-datasource.username (AD-12)");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu DB_SYNC_PASSWORD (app.catalog.sync-datasource.password) cho datasource sync_reader (AD-12)");
        }
    }

    /** Không in mật khẩu ra log. */
    @Override
    public String toString() {
        return "SyncReaderProperties[url=" + url + ", username=" + username + ", password=****]";
    }
}
