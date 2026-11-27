package vn.nhom17.banhang.platform;

import java.io.InputStream;
import java.util.UUID;

/** File nhập đã ghép và xác minh, dùng trong transaction của worker. */
public interface ImportObjectUploads {
    enum Kind { PRODUCTS, PURCHASE_RECEIPT }

    record VerifiedUpload(UUID uploadId, Kind kind, String fileName, long sizeBytes, String fingerprint) {}

    boolean enabled();
    VerifiedUpload claimPreview(UUID uploadId, Kind kind, String fingerprint);
    InputStream open(VerifiedUpload upload);
    void release(VerifiedUpload upload);
}
