-- Schema ai (bảng của dịch vụ AI, chủ: Lân). DDL vẫn chỉ do Flyway của backend chạy (AD-4).
-- ai_service không có quyền gì trên schema app, nên policy dùng hàm riêng ai.current_store_id().

CREATE FUNCTION ai.current_store_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT nullif(current_setting('app.store_id', true), '')::uuid $$;
GRANT EXECUTE ON FUNCTION ai.current_store_id() TO ai_service;

CREATE TABLE ai.product_embedding (
    store_id           uuid        NOT NULL,
    product_id         uuid        NOT NULL,
    embedding          vector(384) NOT NULL,          -- intfloat/multilingual-e5-small, đã chuẩn hóa (AD-12)
    model_version      text        NOT NULL,
    source_changed_at  timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, product_id)
);
-- Chỉ mục HNSW để sau (Deferred: khi một Cửa hàng có trên 10.000 Sản phẩm).

CREATE TABLE ai.ai_quota (
    store_id    uuid    NOT NULL,
    quota_date  date    NOT NULL,                     -- ngày theo giờ Việt Nam (AD-13)
    used        integer NOT NULL DEFAULT 0 CHECK (used >= 0),
    PRIMARY KEY (store_id, quota_date)
);

CREATE TABLE ai.ai_query_log (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     uuid        NOT NULL,
    user_id      uuid,
    question     text        NOT NULL,
    query_id     text,                                -- Q1 … Q9
    params       jsonb,
    answer       jsonb,
    refused      boolean     NOT NULL DEFAULT false,
    duration_ms  integer,
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_query_log_time_idx ON ai.ai_query_log (store_id, created_at DESC);

-- Bảng toàn cục, không có dữ liệu của Cửa hàng nào (AD-5 mục 1), nên không bật RLS.
CREATE TABLE ai.sync_cursor (
    name        text PRIMARY KEY,                     -- 'catalog.product-changes'
    cursor      text,                                 -- nextCursor mờ do change-feed trả (docs/api/contracts.md)
    updated_at  timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['ai.product_embedding', 'ai.ai_quota', 'ai.ai_query_log'] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (store_id = ai.current_store_id()) '
                       'WITH CHECK (store_id = ai.current_store_id())', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON ai.product_embedding, ai.ai_quota, ai.ai_query_log, ai.sync_cursor TO ai_service;
