"""Isolated contract checks; no broker, database, or stack is started."""

import os
import unittest
from concurrent.futures import Future
from types import SimpleNamespace
from unittest.mock import patch

import exporter


def completed(value):
    future = Future()
    future.set_result(value)
    return future


class FakeAdmin:
    def __init__(self, committed=7):
        self.calls = 0
        self.committed = committed

    def list_topics(self, **_kwargs):
        partition = SimpleNamespace(error=None, leader=1, replicas=[1], isrs=[1])
        topic = SimpleNamespace(error=None, partitions={0: partition})
        return SimpleNamespace(brokers={1: object()}, controller_id=1,
                               topics={"sales.order-commands.v1": topic})

    def list_offsets(self, requested, **_kwargs):
        self.calls += 1
        offset = 3 if self.calls == 1 else 10
        return {key: completed(SimpleNamespace(offset=offset)) for key in requested}

    def list_consumer_group_offsets(self, requests, **_kwargs):
        request = requests[0]
        committed = [] if self.committed is None else [
            SimpleNamespace(topic="sales.order-commands.v1", partition=0,
                            offset=self.committed, error=None)
        ]
        return {request.group_id: completed(SimpleNamespace(topic_partitions=committed))}


class FakeConnection:
    def __init__(self, rows):
        self.rows = iter(rows)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, _query):
        row = next(self.rows)
        return SimpleNamespace(fetchone=lambda: row, fetchall=lambda: row)


class ExporterContractTests(unittest.TestCase):
    def setUp(self):
        self.environ = patch.dict(os.environ, {
            "SALES_GROUP": "sales-order-worker-v1",
            "BACKOFFICE_GROUP": "backoffice-write-worker-v1",
            "BACKOFFICE_FILE_GROUP": "backoffice-file-worker-v1",
            "CDC_GROUP": "banhang-warehouse-cdc",
            "CDC_TOPIC": "banhang.reporting",
            "WAREHOUSE_HOST": "warehouse",
            "WAREHOUSE_DATABASE": "banhang_warehouse",
            "WAREHOUSE_USER": "infra_monitor",
            "WAREHOUSE_PASSWORD": "fixture-secret",
        })
        self.environ.start()
        self.addCleanup(self.environ.stop)

    def test_kafka_lag_uses_durable_group_offset(self):
        metrics, low, high = exporter.kafka_metrics(FakeAdmin())
        output = metrics.render()
        self.assertEqual(3, low["sales.order-commands.v1", 0])
        self.assertEqual(10, high["sales.order-commands.v1", 0])
        self.assertIn('infra_kafka_group_lag_records{group="sales-order-worker-v1",partition="0",topic="sales.order-commands.v1"} 3', output)
        self.assertIn('infra_kafka_group_offset_present{group="sales-order-worker-v1",partition="0",topic="sales.order-commands.v1"} 1', output)

    def test_missing_group_offset_is_visible_and_uses_retention_floor(self):
        metrics, _, _ = exporter.kafka_metrics(FakeAdmin(committed=None))
        output = metrics.render()
        self.assertIn('infra_kafka_group_offset_present{group="sales-order-worker-v1",partition="0",topic="sales.order-commands.v1"} 0', output)
        self.assertIn('infra_kafka_group_lag_records{group="sales-order-worker-v1",partition="0",topic="sales.order-commands.v1"} 7', output)

    def test_cdc_lag_uses_database_checkpoint_and_exports_no_secret(self):
        row = ("banhang.reporting", 6, True, False, False, None, None, None)
        with patch.object(exporter.psycopg, "connect", return_value=FakeConnection([row])):
            output = exporter.cdc_metrics({("banhang.reporting", 0): 3},
                                          {("banhang.reporting", 0): 10}).render()
        self.assertIn("infra_cdc_checkpoint_next_offset 6", output)
        self.assertIn("infra_cdc_lag_records 4", output)
        self.assertNotIn("fixture-secret", output)

    def test_postgres_query_stats_export_aggregates_only(self):
        with patch.object(exporter.psycopg, "connect",
                          return_value=FakeConnection([(12, 2500, 40), (1234567890,),
                                                       [("db-replica-1", 64)], (1024,), (2,)])):
            output = exporter.postgres_query_metrics().render()
        self.assertIn('infra_postgres_query_calls_total{database="banhang"} 12', output)
        self.assertIn('infra_postgres_query_exec_seconds_total{database="banhang"} 2.5', output)
        self.assertIn('infra_postgres_replica_wal_lag_bytes{replica="db-replica-1"} 64', output)
        self.assertIn('infra_postgres_replica_connected{replica="db-replica-2"} 0', output)
        self.assertIn("infra_postgres_wal_bytes_total 1024", output)
        self.assertIn("infra_postgres_lock_waiters 2", output)
        self.assertNotIn("query_id", output)

    def test_failed_collection_retains_last_success_time_but_drops_stale_samples(self):
        snapshot = exporter.Snapshot()
        data = exporter.Samples()
        data.add("infra_cdc_lag_records", 5)
        snapshot.update("cdc", True, data)
        snapshot.update("cdc", False, exporter.Samples())
        output = snapshot.render().decode()
        self.assertIn('infra_metrics_collect_success{source="cdc"} 0', output)
        self.assertIn('infra_metrics_collect_timestamp_seconds{source="cdc"}', output)
        self.assertNotIn("infra_cdc_lag_records", output)


if __name__ == "__main__":
    unittest.main()
