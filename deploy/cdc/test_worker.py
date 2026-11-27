"""Real PostgreSQL contract tests; CDC_TEST_DSN points to a disposable warehouse database."""
import os
import unittest
import uuid
from pathlib import Path

import psycopg

from worker import Mirror, ProtocolError


@unittest.skipUnless(os.environ.get("CDC_TEST_DSN"), "set CDC_TEST_DSN to a disposable PostgreSQL database")
class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.dsn = os.environ["CDC_TEST_DSN"]
        self.connection = psycopg.connect(self.dsn, autocommit=True)
        self.connection.execute("DROP SCHEMA IF EXISTS app CASCADE; DROP SCHEMA IF EXISTS warehouse CASCADE")
        for role in ("warehouse_writer", "warehouse_reader"):
            self.connection.execute("DO $$ BEGIN CREATE ROLE " + role + "; EXCEPTION WHEN duplicate_object THEN NULL; END $$")
        self.connection.execute(Path(__file__).with_name("schema.sql").read_text(encoding="utf-8"))
        self.mirror = Mirror(self.connection, "banhang.reporting")
        self.offset = 0
        self.order_id = str(uuid.uuid4())
        self.store_id = str(uuid.uuid4())

    def tearDown(self):
        self.mirror.abort()
        self.connection.close()

    def send(self, payload):
        result = self.mirror.process(self.offset, payload)
        self.offset += 1
        return result

    def notification(self, kind):
        return {"aggregate_type": "Initial Snapshot", "type": kind, "timestamp": "1800000000000"}

    def row(self, op="c", tx="10:100", lsn=100, order=1, table="sales_order", **fields):
        after = {"id": self.order_id, "store_id": self.store_id, "status": "COMPLETED",
                 "total_amount": 50000, "line_discount_total": 0, "order_discount": 0,
                 "points_discount": 0, "created_at": "2026-11-27T01:00:00Z"} | fields
        return {"op": op, "before": {"id": after["id"]}, "after": None if op == "d" else after,
                "source": {"schema": "app", "table": table, "lsn": lsn, "snapshot": "true" if op == "r" else "false"},
                "transaction": None if op == "r" else {"id": tx, "total_order": order}}

    def state(self):
        return self.connection.execute("SELECT snapshot_completed, next_offset FROM warehouse.cdc_state").fetchone()

    def count_orders(self, connection=None):
        return (connection or self.connection).execute("SELECT count(*) FROM app.sales_order").fetchone()[0]

    def test_snapshot_is_unavailable_until_completed_even_for_empty_source(self):
        self.send(self.notification("STARTED"))
        self.send(self.row(op="r"))
        self.assertEqual((False, 2), self.state())
        self.send(self.notification("COMPLETED"))
        self.assertEqual((True, 3), self.state())
        self.send(self.notification("STARTED"))
        self.assertEqual(0, self.count_orders())
        self.send(self.notification("COMPLETED"))
        self.assertEqual((True, 5), self.state())

    def test_whole_source_transaction_and_checkpoint_become_visible_together(self):
        self.send(self.notification("COMPLETED"))
        self.send({"status": "BEGIN", "id": "10:100"})
        self.send(self.row())
        with psycopg.connect(self.dsn, autocommit=True) as reader:
            self.assertEqual(0, self.count_orders(reader))
            self.assertEqual(1, reader.execute("SELECT next_offset FROM warehouse.cdc_state").fetchone()[0])
        self.assertEqual(4, self.send({"status": "END", "id": "10:100", "event_count": 1}))
        self.assertEqual(1, self.count_orders())
        self.assertEqual((True, 4), self.state())

    def test_crash_before_end_rolls_back_rows_and_restarts_at_durable_checkpoint(self):
        self.send(self.notification("COMPLETED"))
        self.send({"status": "BEGIN", "id": "10:100"})
        self.send(self.row())
        self.mirror.abort()
        self.mirror = Mirror(self.connection, "banhang.reporting")
        self.assertEqual(1, self.mirror.next_offset)
        self.assertEqual(0, self.count_orders())
        self.offset = self.mirror.next_offset
        self.send({"status": "BEGIN", "id": "10:100"})
        self.send(self.row())
        self.send({"status": "END", "id": "10:100", "event_count": 1})
        self.assertEqual(1, self.count_orders())

    def test_duplicate_or_older_transactions_cannot_overwrite_or_resurrect_deleted_rows(self):
        for tx, lsn, op, amount in (("10:100", 100, "c", 50000), ("11:200", 200, "u", 80000),
                                    ("10:100", 100, "c", 50000), ("12:300", 300, "d", 0),
                                    ("11:200", 200, "u", 80000)):
            self.send({"status": "BEGIN", "id": tx})
            self.send(self.row(op=op, tx=tx, lsn=lsn, total_amount=amount))
            self.send({"status": "END", "id": tx, "event_count": 1})
            if lsn == 100 and self.offset > 3:
                self.assertEqual(80000, self.connection.execute("SELECT total_amount FROM app.sales_order").fetchone()[0])
        self.assertEqual(0, self.count_orders())

    def test_transaction_open_at_snapshot_cutover_can_replace_snapshot_row(self):
        # A transaction can write WAL before the snapshot LSN, then commit after it.
        self.send(self.row(op="r", lsn=900, total_amount=50000))
        self.send(self.notification("COMPLETED"))
        self.send({"status": "BEGIN", "id": "10:1000"})
        self.send(self.row(tx="10:1000", lsn=800, total_amount=80000))
        self.send({"status": "END", "id": "10:1000", "event_count": 1})
        self.assertEqual(80000, self.connection.execute("SELECT total_amount FROM app.sales_order").fetchone()[0])

    def test_wrong_transaction_counts_fail_closed(self):
        self.send({"status": "BEGIN", "id": "10:100"})
        self.send(self.row())
        with self.assertRaises(ProtocolError):
            self.send({"status": "END", "id": "10:100", "event_count": 2})
        self.mirror.abort()
        self.assertEqual(0, self.count_orders())
        self.assertEqual((False, 0), self.state())

    def test_streaming_rows_without_transaction_metadata_and_offset_gaps_fail_closed(self):
        row = self.row()
        row["transaction"] = None
        with self.assertRaises(ProtocolError):
            self.send(row)
        self.assertEqual(0, self.count_orders())
        self.assertEqual((False, 0), self.state())
        with self.assertRaises(ProtocolError):
            Mirror(self.connection, "banhang.reporting").process(5, None)

    def test_polling_only_marks_completed_snapshot_at_kafka_high_watermark_ready(self):
        self.mirror.polled(0)
        self.assertFalse(self.connection.execute("SELECT caught_up FROM warehouse.cdc_state").fetchone()[0])
        self.send(self.notification("COMPLETED"))
        self.mirror.polled(3)
        self.assertFalse(self.connection.execute("SELECT caught_up FROM warehouse.cdc_state").fetchone()[0])
        self.send({"ts_ms": 1800000000000})
        self.send(None)
        self.mirror.polled(3)
        self.assertTrue(self.connection.execute("SELECT caught_up FROM warehouse.cdc_state").fetchone()[0])
        self.assertIsNotNone(self.connection.execute("SELECT last_heartbeat_at FROM warehouse.cdc_state").fetchone()[0])

    def test_snapshot_restart_preserves_an_inflight_report_repeatable_read_snapshot(self):
        self.send(self.row(op="r"))
        self.send(self.notification("COMPLETED"))
        with psycopg.connect(self.dsn) as report:
            report.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            self.assertTrue(report.execute("SELECT snapshot_completed FROM warehouse.cdc_state").fetchone()[0])
            self.send(self.notification("STARTED"))
            # The report chose the old ready checkpoint before reset, so it must retain its rows.
            self.assertEqual(1, self.count_orders(report))
        self.assertEqual(0, self.count_orders())
        self.assertFalse(self.state()[0])

    def test_connector_replay_restarts_same_partial_source_transaction_atomically(self):
        self.send({"status": "BEGIN", "id": "10:100"})
        self.send(self.row(total_amount=10000))
        # Connector died after publishing a prefix, then re-emitted the committed transaction.
        self.send({"status": "BEGIN", "id": "10:100"})
        self.send(self.row(total_amount=50000))
        self.send({"status": "END", "id": "10:100", "event_count": 1})
        self.assertEqual(50000, self.connection.execute("SELECT total_amount FROM app.sales_order").fetchone()[0])
        self.assertEqual(5, self.state()[1])

    def test_postgres_transaction_ids_allow_operation_lsn_to_change_until_end(self):
        # Actual Debezium3.4 PostgreSQL pgoutput IDs from staging: XID stays
        # stable; BEGIN, individual row and END each carry their own WAL LSN.
        self.send({"status": "BEGIN", "id": "851:87668136"})
        self.send(self.row(tx="851:87668136", lsn=87668136))
        self.send(self.row(tx="851:87672336", lsn=87672336, order=2,
                           table="sales_order_line", id=str(uuid.uuid4()),
                           order_id=self.order_id, variant_id=str(uuid.uuid4()), quantity=1, unit_cost=10000))
        self.send({"status": "END", "id": "851:87681400", "event_count": 2})
        self.assertEqual(1, self.count_orders())
        self.assertEqual(1, self.connection.execute("SELECT count(*) FROM app.sales_order_line").fetchone()[0])
        self.assertEqual(4, self.state()[1])

    def test_changed_postgres_xid_is_rejected_even_when_operation_lsn_matches(self):
        self.send({"status": "BEGIN", "id": "851:87668136"})
        with self.assertRaises(ProtocolError):
            self.send(self.row(tx="852:87668136", lsn=87668136))
        self.mirror.abort()
        self.assertEqual(0, self.count_orders())
        self.assertEqual(0, self.state()[1])

    def test_reader_requires_tenant_and_has_no_write_permission(self):
        self.send(self.row(op="r"))
        self.connection.execute("SET ROLE warehouse_reader")
        try:
            self.assertEqual(0, self.count_orders())
            self.connection.execute("SELECT set_config('app.store_id', %s, false)", (self.store_id,))
            self.assertEqual(1, self.count_orders())
            self.connection.execute("SELECT set_config('app.store_id', %s, false)", (str(uuid.uuid4()),))
            self.assertEqual(0, self.count_orders())
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                self.connection.execute("DELETE FROM app.sales_order")
        finally:
            self.connection.execute("RESET ROLE")


if __name__ == "__main__":
    unittest.main()
