-- Run on the primary and warehouse. The primary role propagates to replicas.
-- psql's literal quoting keeps the password out of SQL source and logs.
\getenv monitor_password DB_MONITOR_PASSWORD
SELECT 'CREATE ROLE infra_monitor LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'infra_monitor') \gexec
ALTER ROLE infra_monitor WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 5 PASSWORD :'monitor_password';
GRANT pg_monitor TO infra_monitor;
SELECT format('GRANT CONNECT ON DATABASE %I TO infra_monitor', current_database()) \gexec
