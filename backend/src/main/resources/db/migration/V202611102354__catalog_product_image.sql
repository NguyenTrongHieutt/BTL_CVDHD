-- Module catalog (chủ: Hiếu). Ảnh Sản phẩm (FR-1, SP-01b): một ảnh mỗi Sản phẩm, lưu ngay trong database (demo chạy
-- một máy, reset-demo khôi phục cả database). Không lưu tệp trên ổ đĩa hay dịch vụ ngoài, không thu nhỏ ảnh ở server.
--
-- - content_type lấy từ byte đầu tệp (magic number), không tin Content-Type của client: JPEG, PNG, WebP, tối đa 1 MB.
-- - version tăng mỗi lần tải ảnh lên; product.image_url = '/api/v1/catalog/products/<id>/image?v=<version>', nên đổi
--   ảnh là đổi URL và trình duyệt không giữ ảnh cũ (GET trả Cache-Control: private, max-age=86400).
-- - Gỡ ảnh KHÔNG xóa dòng: content_type = NULL, content rỗng (dòng "đã gỡ"), giữ version. Xóa dòng thì lần tải sau quay
--   về ?v=1, trùng URL của ảnh cũ mà trình duyệt còn giữ trong cache tới 1 ngày.
-- - Không có trong change-feed (ảnh không thuộc searchText): đổi, gỡ ảnh không đổi product.changed_at.

CREATE TABLE app.product_image (
    product_id    uuid PRIMARY KEY REFERENCES app.product (id) ON DELETE RESTRICT,
    store_id      uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    content_type  text        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    content       bytea       NOT NULL CHECK (octet_length(content) <= 1048576),
    version       integer     NOT NULL CHECK (version >= 1),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    -- Dòng đã gỡ ảnh: không có kiểu và không có byte nào; còn ảnh: có cả hai
    CONSTRAINT product_image_removed_ck CHECK ((content_type IS NULL) = (octet_length(content) = 0))
);
CREATE INDEX product_image_store_idx ON app.product_image (store_id, product_id);
SELECT app.enable_tenant_rls('app.product_image');

GRANT SELECT, INSERT, UPDATE, DELETE ON app.product_image TO app_backend;
