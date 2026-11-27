-- pg_stat_statements is preloaded by compose.infra-metrics.yml on the primary.
-- The extension view contains SQL text, but exporter.py reads only aggregates.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
