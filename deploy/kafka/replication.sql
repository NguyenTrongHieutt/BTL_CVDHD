\set ON_ERROR_STOP on
\getenv replica_pw DB_REPLICATION_PASSWORD
\getenv cdc_pw DB_CDC_PASSWORD

-- psql quote biến bằng :'name'; không ghép mật khẩu vào câu lệnh shell.
SELECT 'CREATE ROLE replica_stream LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'replica_stream') \gexec
ALTER ROLE replica_stream WITH LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOBYPASSRLS CONNECTION LIMIT 4 PASSWORD :'replica_pw';

-- Snapshot đọc đủ các cửa hàng trên bốn bảng đã FORCE RLS. Role này tách khỏi app_backend.
SELECT 'CREATE ROLE cdc_reader LOGIN REPLICATION BYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cdc_reader') \gexec
ALTER ROLE cdc_reader WITH LOGIN REPLICATION BYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE
    CONNECTION LIMIT 4 PASSWORD :'cdc_pw';
GRANT CONNECT ON DATABASE banhang TO cdc_reader;
GRANT USAGE ON SCHEMA app TO cdc_reader;

SELECT pg_create_physical_replication_slot('pos_replica_1')
WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name = 'pos_replica_1');
SELECT pg_create_physical_replication_slot('pos_replica_2')
WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name = 'pos_replica_2');

DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_replication_slots
               WHERE slot_name IN ('pos_replica_1', 'pos_replica_2') AND slot_type <> 'physical') THEN
        RAISE EXCEPTION 'Slot replica phải có kiểu physical';
    END IF;
END $$;
