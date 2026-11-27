-- Chỉ worker ghi kết quả; API chờ ACK Kafka trước, không dùng bảng outbox.
CREATE TABLE app.backoffice_write_request (
    store_id         uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    actor_id         uuid        NOT NULL,
    operation_id     uuid        NOT NULL,
    action           varchar(80) NOT NULL,
    idempotency_key  uuid        NOT NULL,
    request_hash     varchar(64) NOT NULL,
    status           text        NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    response_status  integer     NOT NULL CHECK (response_status BETWEEN 200 AND 599),
    result           jsonb,
    problem          jsonb,
    response_headers jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    pruned_at        timestamptz,
    PRIMARY KEY (store_id, actor_id, operation_id),
    CHECK ((status = 'SUCCEEDED' AND problem IS NULL AND response_status < 300)
        OR (status = 'FAILED' AND result IS NULL AND response_status >= 400))
);
CREATE INDEX backoffice_write_request_key_idx
    ON app.backoffice_write_request (store_id, actor_id, action, idempotency_key);
SELECT app.enable_tenant_rls('app.backoffice_write_request');
-- Bản cuối bất biến; retry chỉ đọc lại, không UPDATE hoặc DELETE.
GRANT SELECT, INSERT ON app.backoffice_write_request TO app_backend;

-- Chỉ xóa body lớn sau 7 ngày, giữ khóa/hash/kết quả terminal tối thiểu để phát lại không chạy lại nghiệp vụ.
-- Mỗi lượt tối đa 200 hàng; SECURITY DEFINER chỉ phục vụ bảo trì bảng này, không nhận store/SQL từ người gọi.
CREATE FUNCTION app.prune_backoffice_write_results() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = app, pg_temp
AS $$
DECLARE affected integer;
BEGIN
    WITH expired AS (
        SELECT store_id, actor_id, operation_id FROM app.backoffice_write_request
        WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days' AND pruned_at IS NULL
        ORDER BY created_at LIMIT 200 FOR UPDATE SKIP LOCKED
    )
    UPDATE app.backoffice_write_request AS request
    SET result = NULL, problem = NULL, response_headers = '{}'::jsonb, pruned_at = CURRENT_TIMESTAMP
    FROM expired
    WHERE request.store_id = expired.store_id AND request.actor_id = expired.actor_id
        AND request.operation_id = expired.operation_id;
    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected;
END
$$;
REVOKE ALL ON FUNCTION app.prune_backoffice_write_results() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.prune_backoffice_write_results() TO app_backend;
