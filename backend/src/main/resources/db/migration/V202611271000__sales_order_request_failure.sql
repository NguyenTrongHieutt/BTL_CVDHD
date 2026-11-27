-- Chỉ lưu kết quả cuối an toàn của lệnh Kafka bị từ chối; không giữ payload, JWT hoặc dữ liệu khách hàng.
CREATE TABLE app.sales_order_request_failure (
    store_id     uuid         NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    actor_id     uuid         NOT NULL,
    operation_id uuid         NOT NULL,
    reason       varchar(300) NOT NULL,
    created_at   timestamptz  NOT NULL,
    PRIMARY KEY (store_id, actor_id, operation_id)
);
CREATE INDEX sales_order_request_failure_store_time_idx
    ON app.sales_order_request_failure (store_id, created_at DESC);
SELECT app.enable_tenant_rls('app.sales_order_request_failure');
GRANT SELECT, INSERT ON app.sales_order_request_failure TO app_backend;
