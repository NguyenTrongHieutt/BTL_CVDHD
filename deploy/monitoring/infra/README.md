# Async infrastructure metrics

Add `compose.infra-metrics.yml` after `docker-compose.yml`, `compose.server.yml`,
`compose.kafka.yml`, and `compose.monitoring.yml`. Set a distinct
`DB_MONITOR_PASSWORD` in the deployment environment. The two one-shot setup
services create or update `infra_monitor` with `pg_monitor` on the primary and
warehouse. The warehouse additionally grants this role `SELECT` on
`warehouse.cdc_state`. Physical replication carries the primary role to both
standbys. The role has no access to application rows or Kafka payloads.

The primary command preserves all `compose.kafka.yml` PostgreSQL settings and
adds `shared_preload_libraries=pg_stat_statements` with at most 1,000 tracked
statement shapes. This causes a primary restart when the overlay is first
applied. The setup job then creates the extension in `banhang`. Query metrics
sum `calls`, `total_exec_time`, and `rows` for that database. They contain no
query text, query ID, user, or tenant label. PostgreSQL's counters begin when
the extension starts collecting and can reset after restart or stats reset.

Prometheus scrape targets:

| Job | Targets | Path |
| --- | --- | --- |
| `postgres` | `pg-exporter-primary:9187`, `pg-exporter-replica-1:9187`, `pg-exporter-replica-2:9187`, `pg-exporter-warehouse:9187` | `/metrics` |
| `infra-metrics` | `infra-metrics:9198` | `/metrics` |

The official PostgreSQL exporter reads activity, database transaction
counters, locks, replication and slot status, WAL, checkpointer, and standby
WAL receiver views. Its high-cardinality per-table and settings collectors
are disabled. `infra-metrics` uses the existing pinned `confluent-kafka` and
`psycopg` libraries in `banhang-cdc:latest`. It polls every 15 seconds and
serves a cached response. Each source has
`infra_metrics_collect_success{source="kafka|postgres_queries|cdc"}` and
`infra_metrics_collect_timestamp_seconds` so alerts can distinguish absent
or stale reads from zero lag.

Stable aggregate panels can use:

- `infra_postgres_query_calls_total`,
  `infra_postgres_query_exec_seconds_total`, and
  `infra_postgres_query_rows_total` for primary SQL throughput and execution
  time. Divide the execution-time rate by the calls rate for mean query time.
- `infra_postgres_replica_connected{replica}` and
  `infra_postgres_replica_wal_lag_bytes{replica}` for the two physical standbys.
  Missing replay LSN produces no lag sample; the connected metric remains 1.
- `infra_postgres_wal_bytes_total` and `infra_postgres_lock_waiters` for primary
  WAL generation and live lock waits.
- `infra_kafka_broker_count`, `infra_kafka_controller_present`,
  `infra_kafka_under_replicated_partitions`,
  `infra_kafka_offline_partitions`, and the per-partition ISR/leader series for
  cluster health. Topic names are a fixed allowlist.
- `infra_kafka_group_lag_records{group,topic,partition}` for the three command
  worker groups and Kafka's CDC group. This reads broker-committed offsets.
  `infra_kafka_group_offset_present` marks groups without a committed offset.
  `infra_kafka_group_retention_gap_records` flags an offset older than the
  retention floor. The offset span is exported for four non-compacted data
  topics; it is not disk bytes or an exact count of live records.
- `infra_cdc_checkpoint_next_offset`, `infra_cdc_lag_records`,
  `infra_cdc_retention_gap_records`, `infra_cdc_checkpoint_ahead_records`,
  `infra_cdc_snapshot_completed`, `infra_cdc_caught_up`, and
  `infra_cdc_error_present`. This checkpoint is committed with warehouse rows
  and is authoritative even if the Kafka consumer commit fails. The last
  poll/commit/heartbeat timestamps are absent until first set.

No exporter port is published on the host. The setup services must complete
successfully before the PostgreSQL exporters start. The infra exporter starts
after Kafka, warehouse, and primary role setup; it reports collection failure
until the warehouse CDC table is available. Driver exceptions are logged by
type only to avoid logging credentials.

Isolated verification: run `python -m unittest -v test_exporter` from this
directory using the `banhang-cdc:latest` image. This tests metric contracts
with fake Kafka metadata and SQL results and does not start the stack.
