-- Module inventory (chủ: Anh). AD-3, AD-6 (đơn vị Tồn kho là variant_id), AD-14, AD-15 (Giá vốn một nguồn).

CREATE TABLE app.stock (
    store_id       uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    variant_id     uuid        NOT NULL REFERENCES app.product_variant (id) ON DELETE RESTRICT,
    quantity       integer     NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    avg_unit_cost  bigint      NOT NULL DEFAULT 0 CHECK (avg_unit_cost >= 0),  -- nguồn Giá vốn DUY NHẤT (AD-15)
    min_quantity   integer     NOT NULL DEFAULT 0 CHECK (min_quantity >= 0),   -- Ngưỡng tồn tối thiểu (FR-26)
    is_active      boolean     NOT NULL DEFAULT true,                          -- bản sao trạng thái từ catalog (AD-14)
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, variant_id)
);
SELECT app.enable_tenant_rls('app.stock');

CREATE TABLE app.stock_ledger (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    variant_id      uuid        NOT NULL REFERENCES app.product_variant (id) ON DELETE RESTRICT,
    document_type   text        NOT NULL CHECK (document_type IN
                        ('OPENING', 'SALE', 'SALE_CANCEL', 'SALE_RETURN', 'PURCHASE_RECEIPT', 'STOCKTAKE')),
    document_id     uuid        NOT NULL,
    line_no         integer     NOT NULL,
    delta           integer     NOT NULL,
    unit_cost       bigint      NOT NULL CHECK (unit_cost >= 0),
    quantity_after  integer     NOT NULL CHECK (quantity_after >= 0),
    avg_cost_after  bigint      NOT NULL CHECK (avg_cost_after >= 0),
    lot_id          uuid,
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- Gọi lại StockService.apply với cùng chứng từ thì không làm gì (AD-6)
    CONSTRAINT stock_ledger_document_line_uq UNIQUE (store_id, document_type, document_id, line_no)
);
CREATE INDEX stock_ledger_variant_time_idx ON app.stock_ledger (store_id, variant_id, created_at);
CREATE INDEX stock_ledger_time_idx ON app.stock_ledger (store_id, created_at);
SELECT app.enable_tenant_rls('app.stock_ledger');

CREATE TABLE app.lot (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id              uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    variant_id            uuid        NOT NULL REFERENCES app.product_variant (id) ON DELETE RESTRICT,
    lot_code              text,
    expiry_date           date,
    received_quantity     integer     NOT NULL CHECK (received_quantity > 0),
    remaining_quantity    integer     NOT NULL CHECK (remaining_quantity >= 0),
    source_document_type  text        NOT NULL,
    source_document_id    uuid        NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now()
);
-- FEFO: trừ lô hết hạn trước (AD-6, FR-8)
CREATE INDEX lot_fefo_idx ON app.lot (store_id, variant_id, expiry_date);
SELECT app.enable_tenant_rls('app.lot');

CREATE TABLE app.purchase_receipt (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    code          text,
    supplier_id   uuid        NOT NULL REFERENCES app.supplier (id) ON DELETE RESTRICT,
    received_at   timestamptz NOT NULL,
    note          text,
    total_amount  bigint      NOT NULL CHECK (total_amount >= 0),
    created_by    uuid,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX purchase_receipt_code_uq ON app.purchase_receipt (store_id, code) WHERE code IS NOT NULL;
CREATE INDEX purchase_receipt_time_idx ON app.purchase_receipt (store_id, received_at);
SELECT app.enable_tenant_rls('app.purchase_receipt');

CREATE TABLE app.purchase_receipt_line (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     uuid    NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    receipt_id   uuid    NOT NULL REFERENCES app.purchase_receipt (id) ON DELETE CASCADE,
    line_no      integer NOT NULL,
    variant_id   uuid    NOT NULL REFERENCES app.product_variant (id) ON DELETE RESTRICT,
    quantity     integer NOT NULL CHECK (quantity > 0),
    unit_cost    bigint  NOT NULL CHECK (unit_cost >= 0),
    lot_code     text,
    expiry_date  date,
    UNIQUE (store_id, receipt_id, line_no)
);
SELECT app.enable_tenant_rls('app.purchase_receipt_line');

CREATE TABLE app.stocktake (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    note          text,
    created_by    uuid,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX stocktake_time_idx ON app.stocktake (store_id, created_at);
SELECT app.enable_tenant_rls('app.stocktake');

CREATE TABLE app.stocktake_line (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id          uuid    NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    stocktake_id      uuid    NOT NULL REFERENCES app.stocktake (id) ON DELETE CASCADE,
    line_no           integer NOT NULL,
    variant_id        uuid    NOT NULL REFERENCES app.product_variant (id) ON DELETE RESTRICT,
    system_quantity   integer NOT NULL CHECK (system_quantity >= 0),
    counted_quantity  integer NOT NULL CHECK (counted_quantity >= 0),
    reason            text,
    UNIQUE (store_id, stocktake_id, line_no)
);
SELECT app.enable_tenant_rls('app.stocktake_line');

GRANT SELECT, INSERT, UPDATE, DELETE ON app.stock, app.stock_ledger, app.lot, app.purchase_receipt,
    app.purchase_receipt_line, app.stocktake, app.stocktake_line TO app_backend;
