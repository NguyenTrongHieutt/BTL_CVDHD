-- This is the only application table the monitoring role may read.
GRANT USAGE ON SCHEMA warehouse TO infra_monitor;
GRANT SELECT ON warehouse.cdc_state TO infra_monitor;
