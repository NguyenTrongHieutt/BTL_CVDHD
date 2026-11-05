-- Module platform (chủ: Hiếu). AD-3, AD-5, AD-7, AD-8, AD-9.
-- Hàm dùng chung cho mọi module ----------------------------------------------------------------

-- Cửa hàng của transaction hiện tại. Chưa đặt ngữ cảnh thì trả NULL, nên policy RLS trả 0 dòng thay vì lỗi.
CREATE FUNCTION app.current_store_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT nullif(current_setting('app.store_id', true), '')::uuid $$;

-- Bọc unaccent thành IMMUTABLE để dùng được trong index tìm không dấu (AD-4, FR-5).
CREATE FUNCTION app.f_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
RETURN public.unaccent('public.unaccent'::regdictionary, $1);

-- Bật ENABLE + FORCE RLS với policy theo store_id cho một bảng (AD-5).
CREATE FUNCTION app.enable_tenant_rls(tbl regclass) RETURNS void
    LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', tbl);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', tbl);
    EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (store_id = app.current_store_id()) '
                   'WITH CHECK (store_id = app.current_store_id())', tbl);
END
$$;
REVOKE ALL ON FUNCTION app.enable_tenant_rls(regclass) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.current_store_id() TO app_backend, sync_reader;
GRANT EXECUTE ON FUNCTION app.f_unaccent(text) TO app_backend, sync_reader;

-- Bảng ---------------------------------------------------------------------------------------

CREATE TABLE app.store (
    id                   uuid PRIMARY KEY,
    name                 text        NOT NULL,
    phone                text,
    address              text,
    invoice_footer       text,
    -- VietQR (AD-22, FR-48)
    bank_bin             text CHECK (bank_bin ~ '^[0-9]{6}$'),
    bank_account_no      text,
    bank_account_name    text,
    -- Tích điểm (FR-21): số tiền để được 1 điểm, giá trị 1 điểm khi đổi
    loyalty_earn_amount  bigint      NOT NULL DEFAULT 10000 CHECK (loyalty_earn_amount > 0),
    loyalty_point_value  bigint      NOT NULL DEFAULT 1000 CHECK (loyalty_point_value >= 0),
    -- Cảnh báo hết hạn (FR-8)
    expiry_warning_days  integer     NOT NULL DEFAULT 30 CHECK (expiry_warning_days >= 0),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);
-- Bảng store dùng chính id làm khóa phân tách (AD-5 mục 1).
ALTER TABLE app.store ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.store FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.store
    USING (id = app.current_store_id()) WITH CHECK (id = app.current_store_id());

CREATE TABLE app.app_user (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id       uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    login_key      text        NOT NULL CHECK (login_key = lower(login_key)),
    display_name   text        NOT NULL,
    password_hash  text        NOT NULL,
    role           text        NOT NULL CHECK (role IN ('OWNER', 'STAFF')),
    status         text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED')),
    phone          text,
    email          text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
-- login_key unique TOÀN HỆ THỐNG (AD-9). Trùng thì bắt lỗi unique, trả 409 /errors/platform/login-taken.
CREATE UNIQUE INDEX app_user_login_key_uq ON app.app_user (login_key);
CREATE INDEX app_user_store_idx ON app.app_user (store_id, role);
SELECT app.enable_tenant_rls('app.app_user');

CREATE TABLE app.order_counter (
    store_id    uuid PRIMARY KEY REFERENCES app.store (id) ON DELETE RESTRICT,
    next_value  bigint NOT NULL DEFAULT 1 CHECK (next_value >= 1)
);
SELECT app.enable_tenant_rls('app.order_counter');

CREATE TABLE app.audit_log (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    uuid        NOT NULL REFERENCES app.store (id) ON DELETE RESTRICT,
    actor_id    uuid,
    action      text        NOT NULL,          -- <module>.<entity>.<verb>
    entity      text        NOT NULL,
    entity_id   text,
    before      jsonb,
    after       jsonb,
    request_id  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (octet_length(coalesce(before::text, '')) <= 8192),
    CHECK (octet_length(coalesce(after::text, '')) <= 8192)
);
CREATE INDEX audit_log_store_time_idx ON app.audit_log (store_id, created_at DESC);
SELECT app.enable_tenant_rls('app.audit_log');

-- Chống tạo trùng chứng từ (AD-7). Một bảng chung, chỉ platform ghi qua IdempotencyService.
CREATE TABLE app.idempotency_record (
    store_id         uuid        NOT NULL,
    document_type    text        NOT NULL,
    idempotency_key  uuid        NOT NULL,
    request_hash     text        NOT NULL,
    document_id      uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, document_type, idempotency_key)
);
SELECT app.enable_tenant_rls('app.idempotency_record');

-- Ngoại lệ đọc vượt Cửa hàng duy nhất của backend: tra cứu khi đăng nhập (AD-5 mục 6).
CREATE FUNCTION app.auth_lookup(p_login_key text)
    RETURNS TABLE (user_id uuid, store_id uuid, password_hash text, role text, status text, display_name text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = app, pg_temp
AS $$
    SELECT u.id, u.store_id, u.password_hash, u.role, u.status, u.display_name
    FROM app.app_user u
    WHERE u.login_key = lower(p_login_key)
$$;
REVOKE ALL ON FUNCTION app.auth_lookup(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.auth_lookup(text) TO app_backend;

-- Quyền ----------------------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON app.store, app.app_user, app.order_counter, app.idempotency_record TO app_backend;
-- Nhật ký thao tác: không UPDATE, không DELETE.
GRANT SELECT, INSERT ON app.audit_log TO app_backend;
