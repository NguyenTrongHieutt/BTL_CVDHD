-- Module catalog (chủ: Hiếu). AD-3, AD-6 (mọi Sản phẩm có ít nhất một Biến thể), AD-12 (change-feed), AD-19 (xóa mềm).

CREATE TABLE app.category (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX category_name_uq ON app.category (store_id, lower(name));
SELECT app.enable_tenant_rls('app.category');

CREATE TABLE app.product (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    category_id   uuid        REFERENCES app.category (id) ON DELETE RESTRICT,  -- FR-4: không xóa Danh mục còn Sản phẩm
    name          text        NOT NULL,
    unit          text        NOT NULL DEFAULT 'cái',
    description   text,
    image_url     text,
    has_variants  boolean     NOT NULL DEFAULT false,
    status        text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    changed_at    timestamptz NOT NULL DEFAULT now(),   -- AD-12: đổi tên Danh mục/Biến thể cũng cập nhật cột này
    deleted_at    timestamptz
);
CREATE INDEX product_category_idx ON app.product (store_id, category_id);
CREATE INDEX product_changed_idx ON app.product (store_id, changed_at);
SELECT app.enable_tenant_rls('app.product');

CREATE TABLE app.product_variant (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    product_id  uuid        NOT NULL REFERENCES app.product (id) ON DELETE RESTRICT,
    sku         text        NOT NULL,
    barcode     text,
    name        text,                         -- nhãn Biến thể, ví dụ "Đỏ / L"; NULL với Biến thể mặc định
    attributes  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    sale_price  bigint      NOT NULL CHECK (sale_price >= 0),
    is_default  boolean     NOT NULL DEFAULT false,
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);
CREATE UNIQUE INDEX product_variant_sku_uq ON app.product_variant (store_id, sku) WHERE deleted_at IS NULL;
-- FR-1: mã vạch duy nhất trong một Cửa hàng; hai Cửa hàng được trùng nhau.
CREATE UNIQUE INDEX product_variant_barcode_uq ON app.product_variant (store_id, barcode)
    WHERE deleted_at IS NULL AND barcode IS NOT NULL;
CREATE UNIQUE INDEX product_variant_default_uq ON app.product_variant (store_id, product_id) WHERE is_default;
CREATE INDEX product_variant_product_idx ON app.product_variant (store_id, product_id);
SELECT app.enable_tenant_rls('app.product_variant');

CREATE TABLE app.customer (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    name        text,                         -- FR-14: tạo nhanh chỉ cần số điện thoại
    phone       text        NOT NULL,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);
-- FR-18: số điện thoại duy nhất trong một Cửa hàng.
CREATE UNIQUE INDEX customer_phone_uq ON app.customer (store_id, phone) WHERE deleted_at IS NULL;
SELECT app.enable_tenant_rls('app.customer');

CREATE TABLE app.supplier (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    code        text,
    name        text        NOT NULL,
    phone       text,
    address     text,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);
CREATE UNIQUE INDEX supplier_code_uq ON app.supplier (store_id, code) WHERE deleted_at IS NULL AND code IS NOT NULL;
SELECT app.enable_tenant_rls('app.supplier');

-- Change-feed cho dịch vụ AI (AD-12). security_invoker: chạy bằng quyền của sync_reader (BYPASSRLS).
CREATE VIEW app.catalog_product_changes WITH (security_invoker = true) AS
SELECT p.store_id,
       p.id AS product_id,
       concat_ws(' ', p.name, c.name,
                 string_agg(DISTINCT concat_ws(' ', v.name, v.attributes::text), ' ')
                     FILTER (WHERE v.deleted_at IS NULL)) AS search_text,
       CASE WHEN p.status = 'ACTIVE' AND p.deleted_at IS NULL THEN 'ACTIVE' ELSE 'INACTIVE' END AS status,
       p.changed_at
FROM app.product p
LEFT JOIN app.category c ON c.id = p.category_id
LEFT JOIN app.product_variant v ON v.product_id = p.id
GROUP BY p.store_id, p.id, p.name, c.name, p.status, p.deleted_at, p.changed_at;

GRANT SELECT, INSERT, UPDATE, DELETE ON app.category, app.product, app.product_variant, app.customer, app.supplier TO app_backend;
GRANT SELECT ON app.catalog_product_changes TO app_backend;
GRANT SELECT ON app.product, app.product_variant, app.category, app.catalog_product_changes TO sync_reader;
