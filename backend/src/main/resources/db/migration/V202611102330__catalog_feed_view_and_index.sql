-- Module catalog (chủ: Hiếu). Change-feed cho dịch vụ AI (AD-12).
--
-- 1. search_text không còn chữ "{}" của Biến thể mặc định: ghép tên Sản phẩm, tên Danh mục, rồi nhãn và GIÁ TRỊ
--    thuộc tính (jsonb_each_text) của các Biến thể chưa xóa mềm; bỏ phần rỗng hoặc chỉ có khoảng trắng. Thứ tự cố
--    định: Biến thể mặc định trước, rồi theo ngày tạo, id; trong một Biến thể: nhãn trước, rồi giá trị theo tên khóa.
--    Cùng cột, cùng kiểu với view cũ; vẫn security_invoker (chạy bằng quyền của người đọc: sync_reader BYPASSRLS,
--    app_backend vẫn chịu RLS).
-- 2. Index (changed_at, id) cho thứ tự đọc vượt Cửa hàng của feed. Ngoại lệ có ghi lại của quy ước "index bắt đầu
--    bằng store_id" (docs/database.md), đi cùng ngoại lệ đọc vượt Cửa hàng AD-5 mục 6.

CREATE OR REPLACE VIEW app.catalog_product_changes WITH (security_invoker = true) AS
SELECT p.store_id,
       p.id AS product_id,
       concat_ws(' ', nullif(btrim(p.name), ''), nullif(btrim(c.name), ''), variant_text.text) AS search_text,
       CASE WHEN p.status = 'ACTIVE' AND p.deleted_at IS NULL THEN 'ACTIVE' ELSE 'INACTIVE' END AS status,
       p.changed_at
FROM app.product p
LEFT JOIN app.category c ON c.id = p.category_id
LEFT JOIN LATERAL (
    SELECT string_agg(part.text, ' ' ORDER BY v.is_default DESC, v.created_at, v.id, part.ord, part.key) AS text
    FROM app.product_variant v
    CROSS JOIN LATERAL (
        SELECT 0 AS ord, NULL::text AS key, nullif(btrim(v.name), '') AS text
        UNION ALL
        SELECT 1, a.key, nullif(btrim(a.value), '')
        -- attributes không có CHECK là object: giá trị khác object coi như không có thuộc tính, không làm hỏng feed
        FROM jsonb_each_text(CASE WHEN jsonb_typeof(v.attributes) = 'object' THEN v.attributes ELSE '{}'::jsonb END) AS a
    ) AS part
    WHERE v.product_id = p.id
      AND v.deleted_at IS NULL
      AND part.text IS NOT NULL
) AS variant_text ON true;

GRANT SELECT ON app.catalog_product_changes TO app_backend;
GRANT SELECT ON app.catalog_product_changes TO sync_reader;

-- Thứ tự (changed_at, product_id) của feed đi qua mọi Cửa hàng, nên index không bắt đầu bằng store_id.
CREATE INDEX product_changed_feed_idx ON app.product (changed_at, id);
