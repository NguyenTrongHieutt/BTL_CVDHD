-- Module catalog (chủ: Hiếu). Nhập Sản phẩm từ Excel (FR-6, SP-06).
--
-- Mỗi lần nhập thành công là một chứng từ (loại CATALOG_PRODUCT_IMPORT của idempotency_record, AD-7: có thể kèm Tồn
-- kho đầu kỳ OPENING của nhiều Sản phẩm). Bảng giữ nguyên phản hồi của lần nhập ({imported, failed, createdCategories}),
-- để gửi lại cùng Idempotency-Key, cùng file trả đúng kết quả lần đầu mà không nhập lại. Ghi trong cùng transaction
-- với các Sản phẩm vừa nhập: lần nhập bị rollback thì không có dòng nào.

CREATE TABLE app.product_import (
    id          uuid PRIMARY KEY,
    store_id    uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    created_by  uuid,
    imported    integer     NOT NULL CHECK (imported >= 0),
    result      jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX product_import_store_time_idx ON app.product_import (store_id, created_at);
SELECT app.enable_tenant_rls('app.product_import');

-- Kết quả lần nhập không sửa, không xóa
GRANT SELECT, INSERT ON app.product_import TO app_backend;
