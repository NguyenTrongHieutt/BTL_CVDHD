-- Khởi tạo database lần đầu (AD-4, AD-5). Chạy MỘT LẦN bởi superuser khi volume `db` còn trống
-- (docker-entrypoint-initdb.d). Mật khẩu đọc từ biến môi trường của container bằng \getenv (psql 15+).
-- Chủ: Hiếu. Đổi file này phải báo cả nhóm và chạy lại `deploy/reset-demo`.
\set ON_ERROR_STOP on

\getenv owner_pw DB_OWNER_PASSWORD
\getenv backend_pw DB_BACKEND_PASSWORD
\getenv sync_pw DB_SYNC_PASSWORD
\getenv ai_pw DB_AI_PASSWORD

-- Thiếu biến nào thì dừng ngay với thông báo rõ ràng.
\if :{?owner_pw}
\else
  \echo 'Thiếu biến môi trường DB_OWNER_PASSWORD'
  SELECT 1/0;
\endif
\if :{?backend_pw}
\else
  \echo 'Thiếu biến môi trường DB_BACKEND_PASSWORD'
  SELECT 1/0;
\endif
\if :{?sync_pw}
\else
  \echo 'Thiếu biến môi trường DB_SYNC_PASSWORD'
  SELECT 1/0;
\endif
\if :{?ai_pw}
\else
  \echo 'Thiếu biến môi trường DB_AI_PASSWORD'
  SELECT 1/0;
\endif

-- Các role (AD-5)
CREATE ROLE app_owner   LOGIN BYPASSRLS   PASSWORD :'owner_pw';    -- Flyway, chủ mọi object
CREATE ROLE app_backend LOGIN NOBYPASSRLS PASSWORD :'backend_pw';  -- datasource chính của backend
CREATE ROLE sync_reader LOGIN BYPASSRLS   PASSWORD :'sync_pw';     -- chỉ cho change-feed của catalog
CREATE ROLE ai_service  LOGIN NOBYPASSRLS PASSWORD :'ai_pw';       -- dịch vụ AI, chỉ schema ai

-- Extension cần superuser
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Hai schema, đều do app_owner sở hữu; bảng do Flyway (chạy bằng app_owner) tạo.
CREATE SCHEMA app AUTHORIZATION app_owner;
CREATE SCHEMA ai  AUTHORIZATION app_owner;

SELECT current_database() AS dbname \gset
GRANT CONNECT ON DATABASE :"dbname" TO app_owner, app_backend, sync_reader, ai_service;
GRANT USAGE ON SCHEMA app TO app_backend, sync_reader;
GRANT USAGE ON SCHEMA ai TO ai_service;
