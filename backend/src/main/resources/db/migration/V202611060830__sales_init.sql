-- Module sales (chủ: Anh). AD-3, AD-7, AD-8, AD-15 (unit_cost chụp lúc bán), AD-17, AD-22.

CREATE TABLE app.sales_order (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id             uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    order_code           text        NOT NULL CHECK (order_code ~ '^DH-[0-9]{6}$'),
    customer_id          uuid        REFERENCES app.customer (id) ON DELETE RESTRICT,
    -- FR-15: đơn chỉ được tạo khi xác nhận đã nhận tiền, nên bắt đầu ở COMPLETED
    status               text        NOT NULL CHECK (status IN ('COMPLETED', 'CANCELLED', 'RETURNED')),
    subtotal             bigint      NOT NULL CHECK (subtotal >= 0),
    line_discount_total  bigint      NOT NULL DEFAULT 0 CHECK (line_discount_total >= 0),
    order_discount       bigint      NOT NULL DEFAULT 0 CHECK (order_discount >= 0),
    points_redeemed      integer     NOT NULL DEFAULT 0 CHECK (points_redeemed >= 0),
    points_discount      bigint      NOT NULL DEFAULT 0 CHECK (points_discount >= 0),
    total_amount         bigint      NOT NULL CHECK (total_amount >= 0),   -- FR-10: không bao giờ âm
    created_by           uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    cancelled_at         timestamptz,
    cancelled_by         uuid,
    cancel_reason        text,
    CONSTRAINT sales_order_code_uq UNIQUE (store_id, order_code)
);
CREATE INDEX sales_order_time_idx ON app.sales_order (store_id, created_at);
CREATE INDEX sales_order_customer_idx ON app.sales_order (store_id, customer_id, created_at);
CREATE INDEX sales_order_seller_idx ON app.sales_order (store_id, created_by, created_at);
SELECT app.enable_tenant_rls('app.sales_order');

CREATE TABLE app.sales_order_line (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id              uuid    NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    order_id              uuid    NOT NULL REFERENCES app.sales_order (id) ON DELETE CASCADE,
    line_no               integer NOT NULL,
    variant_id            uuid    NOT NULL REFERENCES app.product_variant (id) ON DELETE RESTRICT,
    product_name          text    NOT NULL,     -- chụp lại lúc bán để in lại hóa đơn
    variant_name          text,
    quantity              integer NOT NULL CHECK (quantity > 0),
    unit_price            bigint  NOT NULL CHECK (unit_price >= 0),
    line_discount         bigint  NOT NULL DEFAULT 0 CHECK (line_discount >= 0),
    order_discount_share  bigint  NOT NULL DEFAULT 0 CHECK (order_discount_share >= 0),
    net_line_amount       bigint  NOT NULL CHECK (net_line_amount >= 0),
    unit_cost             bigint  NOT NULL CHECK (unit_cost >= 0),        -- giá trị StockService.apply trả về
    UNIQUE (store_id, order_id, line_no)
);
CREATE INDEX sales_order_line_variant_idx ON app.sales_order_line (store_id, variant_id);
SELECT app.enable_tenant_rls('app.sales_order_line');

CREATE TABLE app.payment (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    order_id        uuid        NOT NULL REFERENCES app.sales_order (id) ON DELETE CASCADE,
    method          text        NOT NULL CHECK (method IN ('CASH', 'BANK_TRANSFER')),
    amount          bigint      NOT NULL CHECK (amount >= 0),
    cash_received   bigint      CHECK (cash_received >= 0),
    change_amount   bigint      CHECK (change_amount >= 0),
    transfer_ref    text,                       -- AD-22: "TT" + 8 ký tự đầu Idempotency-Key
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_order_idx ON app.payment (store_id, order_id);
SELECT app.enable_tenant_rls('app.payment');

CREATE TABLE app.sales_return (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    order_id        uuid        NOT NULL REFERENCES app.sales_order (id) ON DELETE RESTRICT,
    refund_amount   bigint      NOT NULL CHECK (refund_amount >= 0),
    refund_method   text        NOT NULL CHECK (refund_method IN ('CASH', 'BANK_TRANSFER')),
    reason          text,
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX sales_return_order_idx ON app.sales_return (store_id, order_id);
CREATE INDEX sales_return_time_idx ON app.sales_return (store_id, created_at);
SELECT app.enable_tenant_rls('app.sales_return');

CREATE TABLE app.sales_return_line (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        uuid    NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    return_id       uuid    NOT NULL REFERENCES app.sales_return (id) ON DELETE CASCADE,
    line_no         integer NOT NULL,
    order_line_id   uuid    NOT NULL REFERENCES app.sales_order_line (id) ON DELETE RESTRICT,
    variant_id      uuid    NOT NULL REFERENCES app.product_variant (id) ON DELETE RESTRICT,
    quantity        integer NOT NULL CHECK (quantity > 0),
    refund_amount   bigint  NOT NULL CHECK (refund_amount >= 0),
    UNIQUE (store_id, return_id, line_no)
);
CREATE INDEX sales_return_line_order_line_idx ON app.sales_return_line (store_id, order_line_id);
SELECT app.enable_tenant_rls('app.sales_return_line');

CREATE TABLE app.loyalty_account (
    store_id     uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    customer_id  uuid        NOT NULL REFERENCES app.customer (id) ON DELETE RESTRICT,
    balance      integer     NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, customer_id)
);
SELECT app.enable_tenant_rls('app.loyalty_account');

CREATE TABLE app.loyalty_ledger (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    customer_id     uuid        NOT NULL REFERENCES app.customer (id) ON DELETE RESTRICT,
    delta           integer     NOT NULL,
    reason          text        NOT NULL CHECK (reason IN ('EARN', 'REDEEM', 'REVERSE_EARN', 'REVERSE_REDEEM')),
    document_type   text        NOT NULL,
    document_id     uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX loyalty_ledger_customer_idx ON app.loyalty_ledger (store_id, customer_id, created_at);
SELECT app.enable_tenant_rls('app.loyalty_ledger');

GRANT SELECT, INSERT, UPDATE, DELETE ON app.sales_order, app.sales_order_line, app.payment, app.sales_return,
    app.sales_return_line, app.loyalty_account, app.loyalty_ledger TO app_backend;
