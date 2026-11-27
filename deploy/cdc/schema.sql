-- A separate database: source mirror tables contain only fields used by Q1/Q3.
CREATE SCHEMA app;
CREATE SCHEMA warehouse;
REVOKE ALL ON SCHEMA app, warehouse FROM PUBLIC;
GRANT USAGE ON SCHEMA app, warehouse TO warehouse_writer, warehouse_reader;

CREATE FUNCTION app.current_store_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT nullif(current_setting('app.store_id', true), '')::uuid $$;
REVOKE ALL ON FUNCTION app.current_store_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.current_store_id() TO warehouse_reader;

CREATE TABLE app.sales_order (
    id uuid PRIMARY KEY, store_id uuid NOT NULL, status text NOT NULL,
    total_amount bigint NOT NULL, line_discount_total bigint NOT NULL,
    order_discount bigint NOT NULL, points_discount bigint NOT NULL, created_at timestamptz NOT NULL
);
CREATE TABLE app.sales_order_line (
    id uuid PRIMARY KEY, store_id uuid NOT NULL, order_id uuid NOT NULL, variant_id uuid NOT NULL,
    quantity integer NOT NULL, unit_cost bigint NOT NULL
);
CREATE TABLE app.sales_return (
    id uuid PRIMARY KEY, store_id uuid NOT NULL, order_id uuid NOT NULL,
    refund_amount bigint NOT NULL, created_at timestamptz NOT NULL
);
CREATE TABLE app.sales_return_line (
    id uuid PRIMARY KEY, store_id uuid NOT NULL, return_id uuid NOT NULL,
    order_line_id uuid NOT NULL, variant_id uuid NOT NULL, quantity integer NOT NULL
);
CREATE INDEX ON app.sales_order (store_id, created_at);
CREATE INDEX ON app.sales_order_line (store_id, order_id);
CREATE INDEX ON app.sales_return (store_id, created_at);
CREATE INDEX ON app.sales_return_line (store_id, return_id);

DO $$
DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['sales_order', 'sales_order_line', 'sales_return', 'sales_return_line'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', relation);
        EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', relation);
        EXECUTE format('CREATE POLICY reader_tenant ON app.%I FOR SELECT TO warehouse_reader USING (store_id = app.current_store_id())', relation);
        EXECUTE format('CREATE POLICY cdc_writer ON app.%I TO warehouse_writer USING (true) WITH CHECK (true)', relation);
        EXECUTE format('GRANT SELECT ON app.%I TO warehouse_reader', relation);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO warehouse_writer', relation);
    END LOOP;
END $$;

CREATE TABLE warehouse.cdc_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    topic text NOT NULL DEFAULT 'banhang.reporting',
    next_offset bigint NOT NULL DEFAULT 0 CHECK (next_offset >= 0),
    snapshot_completed boolean NOT NULL DEFAULT false,
    caught_up boolean NOT NULL DEFAULT false,
    last_heartbeat_at timestamptz,
    last_commit_at timestamptz,
    last_poll_at timestamptz,
    last_error text
);
INSERT INTO warehouse.cdc_state (singleton) VALUES (true);

-- Retain delete versions too: a replayed old source transaction must never resurrect a row.
CREATE TABLE warehouse.row_version (
    table_name text NOT NULL,
    row_id uuid NOT NULL,
    source_lsn numeric(20, 0) NOT NULL,
    source_order bigint NOT NULL,
    PRIMARY KEY (table_name, row_id)
);
GRANT SELECT ON warehouse.cdc_state TO warehouse_reader;
GRANT SELECT, INSERT, UPDATE, DELETE ON warehouse.cdc_state, warehouse.row_version TO warehouse_writer;
