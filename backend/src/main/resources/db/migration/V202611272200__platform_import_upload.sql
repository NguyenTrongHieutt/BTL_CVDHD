CREATE TABLE app.import_upload (
    store_id uuid NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    id uuid NOT NULL,
    actor_id uuid NOT NULL,
    request_key uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('PRODUCTS', 'PURCHASE_RECEIPT')),
    file_name text NOT NULL,
    object_key text NOT NULL UNIQUE,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    part_bytes integer NOT NULL CHECK (part_bytes > 0),
    part_count integer NOT NULL CHECK (part_count BETWEEN 1 AND 32),
    status text NOT NULL CHECK (status IN ('CREATING','OPEN','READY','RELEASING','RELEASED','ABORTING','CANCELLED')),
    remote_id text,
    manifest jsonb,
    receipts jsonb NOT NULL DEFAULT '{}'::jsonb,
    object_etag text,
    version_id text,
    preview_fingerprint text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (store_id,id),
    UNIQUE (store_id,actor_id,request_key)
);
CREATE INDEX import_upload_cleanup_idx ON app.import_upload (expires_at)
    WHERE status NOT IN ('RELEASED','CANCELLED');
SELECT app.enable_tenant_rls('app.import_upload');
GRANT SELECT, INSERT, UPDATE ON app.import_upload TO app_backend;

-- Tổng ngân sách không đưa metadata của cửa hàng khác ra API.
CREATE FUNCTION app.import_upload_reserved_bytes() RETURNS bigint
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = app, pg_temp
    AS $$ SELECT coalesce(sum(size_bytes), 0)::bigint FROM app.import_upload
          WHERE status NOT IN ('RELEASED','CANCELLED') $$;
CREATE FUNCTION app.import_upload_cleanup_candidates() RETURNS TABLE(store_id uuid, actor_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = app, pg_temp
    AS $$ SELECT u.store_id, u.actor_id, u.id FROM app.import_upload u
          WHERE u.status NOT IN ('RELEASED','CANCELLED') AND
          (u.expires_at <= now() OR u.status = 'ABORTING' OR u.status = 'RELEASING')
          ORDER BY u.expires_at LIMIT 20 $$;
REVOKE ALL ON FUNCTION app.import_upload_reserved_bytes() FROM PUBLIC;
REVOKE ALL ON FUNCTION app.import_upload_cleanup_candidates() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.import_upload_reserved_bytes() TO app_backend;
GRANT EXECUTE ON FUNCTION app.import_upload_cleanup_candidates() TO app_backend;
