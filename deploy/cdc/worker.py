"""Ordered Debezium -> PostgreSQL reporting mirror, with a database-owned checkpoint.

All rows, transaction metadata, snapshot notifications and heartbeats MUST share one
non-compacted Kafka topic with exactly one partition. The worker refuses other layouts.
Kafka commits follow the warehouse commit; restart seeks the warehouse offset, so a
crash between those commits is harmless. A PostgreSQL advisory lock permits one writer.
"""
import json
import logging
import os
import signal
import sys
import time
from datetime import datetime, timezone

import psycopg
from psycopg import sql


TABLES = {
    "sales_order": ("id", "store_id", "status", "total_amount", "line_discount_total",
                    "order_discount", "points_discount", "created_at"),
    "sales_order_line": ("id", "store_id", "order_id", "variant_id", "quantity", "unit_cost"),
    "sales_return": ("id", "store_id", "order_id", "refund_amount", "created_at"),
    "sales_return_line": ("id", "store_id", "return_id", "order_line_id", "variant_id", "quantity"),
}
LOG = logging.getLogger("warehouse-cdc")


class ProtocolError(RuntimeError):
    """Stop instead of publishing an incomplete or ambiguously ordered mirror."""


def transaction_xid(identifier):
    """PostgreSQL Debezium IDs are XID:operation-LSN, not a constant full ID."""
    if not isinstance(identifier, str):
        raise ProtocolError("missing PostgreSQL transaction identifier")
    xid, separator, lsn = identifier.partition(":")
    if not xid.isdecimal() or (separator and not lsn.isdecimal()):
        raise ProtocolError("malformed PostgreSQL transaction identifier")
    return xid


class Mirror:
    def __init__(self, connection, topic):
        self.connection = connection
        self.topic = topic
        state = connection.execute("SELECT topic, next_offset FROM warehouse.cdc_state WHERE singleton").fetchone()
        if not state or state[0] != topic:
            raise ProtocolError("warehouse checkpoint topic differs from CDC_TOPIC")
        self.next_offset = state[1]
        self.last_seen = self.next_offset - 1
        self.transaction = None
        self.transaction_id = None
        self.event_count = 0

    def process(self, offset, value):
        """Return a durable next offset, or None while a source transaction is open."""
        if offset < self.next_offset:
            return self.next_offset
        if offset != self.last_seen + 1:
            raise ProtocolError("non-contiguous CDC offsets; rebuild from a complete initial snapshot")
        self.last_seen = offset
        if value is not None and "payload" in value:
            value = value["payload"]

        if value and value.get("status") == "BEGIN":
            xid = transaction_xid(value.get("id"))
            if self.transaction is not None:
                if xid != self.transaction_id:
                    raise ProtocolError("different source transaction began before the previous END")
                # A source restart can leave a published prefix followed by a
                # full replay of the same committed transaction. Discard only
                # the uncommitted prefix, then require the new complete END.
                self.abort()
            self.transaction = self.connection.transaction()
            self.transaction.__enter__()
            self.transaction_id = xid
            self.event_count = 0
            return None

        if value and value.get("status") == "END":
            if self.transaction is None or transaction_xid(value.get("id")) != self.transaction_id:
                raise ProtocolError("source END without matching BEGIN")
            if int(value["event_count"]) != self.event_count:
                raise ProtocolError("source transaction is incomplete")
            self._checkpoint(offset + 1)
            self.transaction.__exit__(None, None, None)
            self.transaction = None
            self.transaction_id = None
            self.next_offset = offset + 1
            return self.next_offset

        if self.transaction is not None:
            self._apply(value)
            return None

        with self.connection.transaction():
            self._apply(value)
            self._checkpoint(offset + 1)
        self.next_offset = offset + 1
        return self.next_offset

    def _apply(self, value):
        if value is None:  # Kafka tombstone following a Debezium delete.
            return
        if value.get("aggregate_type") == "Initial Snapshot":
            if self.transaction is not None:
                raise ProtocolError("snapshot marker inside streaming transaction")
            kind = value["type"]
            if kind == "STARTED":
                # DELETE is MVCC-safe. TRUNCATE could empty an in-flight report
                # that already selected the previous ready checkpoint.
                for table in TABLES:
                    self.connection.execute(sql.SQL("DELETE FROM {}").format(sql.Identifier("app", table)))
                self.connection.execute("DELETE FROM warehouse.row_version")
                self.connection.execute("""UPDATE warehouse.cdc_state SET snapshot_completed = false,
                    caught_up = false, last_heartbeat_at = NULL WHERE singleton""")
            elif kind == "COMPLETED":
                self.connection.execute("UPDATE warehouse.cdc_state SET snapshot_completed = true WHERE singleton")
                self._heartbeat(value.get("timestamp"))
            elif kind == "ABORTED":
                self.connection.execute("UPDATE warehouse.cdc_state SET snapshot_completed = false, caught_up = false WHERE singleton")
            # SKIPPED on connector restart never makes a new, empty warehouse ready.
            return
        if "op" not in value:
            if "ts_ms" in value and "status" not in value:
                self._heartbeat(value["ts_ms"])
                return
            raise ProtocolError("unexpected record in unified CDC stream")

        source = value["source"]
        table = source.get("table")
        if source.get("schema") != "app" or table not in TABLES:
            raise ProtocolError("CDC source table is outside reporting allowlist")
        operation = value["op"]
        if operation not in ("c", "u", "d", "r"):
            raise ProtocolError("unsupported CDC operation (TRUNCATE requires a new snapshot)")
        transaction = value.get("transaction")
        if operation == "r":
            if self.transaction is not None:
                raise ProtocolError("snapshot row inside streaming transaction")
            source_order = 0
        else:
            if (not transaction or self.transaction is None
                    or transaction_xid(transaction.get("id")) != self.transaction_id):
                raise ProtocolError("streaming row has no matching transaction metadata")
            self.event_count += 1
            source_order = int(transaction["total_order"])
            if source_order != self.event_count:
                raise ProtocolError("non-contiguous rows in source transaction")
        row = value["before"] if operation == "d" else value["after"]
        if not row or not row.get("id") or (operation != "r" and source.get("lsn") is None):
            raise ProtocolError("CDC row requires a primary key and source LSN")
        # Snapshot rows precede every streamed committed transaction. A transaction
        # may have written WAL before the snapshot LSN but committed after cutover.
        version_lsn = 0 if operation == "r" else int(source["lsn"])
        version = self.connection.execute("""
            INSERT INTO warehouse.row_version (table_name, row_id, source_lsn, source_order)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (table_name, row_id) DO UPDATE
                SET source_lsn = EXCLUDED.source_lsn, source_order = EXCLUDED.source_order
                WHERE (row_version.source_lsn, row_version.source_order) <
                      (EXCLUDED.source_lsn, EXCLUDED.source_order)
            RETURNING row_id
            """, (table, row["id"], version_lsn, source_order)).fetchone()
        if version is None:
            return  # Duplicate/replayed old source transaction, including deleted rows.
        if operation == "d":
            self.connection.execute(sql.SQL("DELETE FROM {} WHERE id = %s").format(sql.Identifier("app", table)), (row["id"],))
            return
        columns = TABLES[table]
        missing = set(columns) - row.keys()
        if missing:
            raise ProtocolError("required reporting fields missing from CDC row")
        identifiers = sql.SQL(", ").join(map(sql.Identifier, columns))
        assignments = sql.SQL(", ").join(sql.SQL("{} = EXCLUDED.{}").format(sql.Identifier(name), sql.Identifier(name))
                                         for name in columns if name != "id")
        self.connection.execute(sql.SQL("INSERT INTO {} ({}) VALUES ({}) ON CONFLICT (id) DO UPDATE SET {}")
                                .format(sql.Identifier("app", table), identifiers,
                                        sql.SQL(", ").join(sql.Placeholder() for _ in columns), assignments),
                                [row[name] for name in columns])

    def _heartbeat(self, timestamp):
        if timestamp is not None:
            at = datetime.fromtimestamp(int(timestamp) / 1000, timezone.utc)
            self.connection.execute("""UPDATE warehouse.cdc_state
                SET last_heartbeat_at = greatest(last_heartbeat_at, %s) WHERE singleton""", (at,))

    def _checkpoint(self, next_offset):
        self.connection.execute("""UPDATE warehouse.cdc_state SET next_offset = %s,
            last_commit_at = clock_timestamp(), caught_up = false, last_error = NULL WHERE singleton""", (next_offset,))

    def polled(self, high_offset):
        if self.transaction is None:
            self.connection.execute("""UPDATE warehouse.cdc_state
                SET last_poll_at = clock_timestamp(), caught_up = snapshot_completed AND next_offset >= %s,
                    last_error = NULL WHERE singleton""", (high_offset,))

    def abort(self):
        if self.transaction is not None:
            self.transaction.__exit__(RuntimeError, RuntimeError("CDC transaction aborted"), None)
            self.transaction = None
            self.transaction_id = None


def connect():
    # libpq PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD environment variables; no URI password escaping.
    return psycopg.connect(connect_timeout=5, autocommit=True, application_name="reporting-cdc")


def healthcheck():
    with connect() as connection:
        row = connection.execute("""SELECT last_error IS NULL AND
            last_poll_at > clock_timestamp() - interval '45 seconds' FROM warehouse.cdc_state WHERE singleton""").fetchone()
        return 0 if row and row[0] else 1


def run():
    from confluent_kafka import Consumer, KafkaException, TopicPartition

    topic = os.environ.get("CDC_TOPIC", "banhang.reporting")
    consumer = Consumer({"bootstrap.servers": os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
                         "group.id": "banhang-warehouse-cdc", "enable.auto.commit": False,
                         "enable.auto.offset.store": False, "auto.offset.reset": "error",
                         "isolation.level": "read_committed"})
    stopping = False

    def stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    with connect() as connection:
        if not connection.execute("SELECT pg_try_advisory_lock(1702702001)").fetchone()[0]:
            raise ProtocolError("another warehouse writer is already active")
        mirror = Mirror(connection, topic)
        try:
            metadata = consumer.list_topics(topic, timeout=20)
            topic_metadata = metadata.topics.get(topic)
            if not topic_metadata or topic_metadata.error or len(topic_metadata.partitions) != 1:
                raise ProtocolError("CDC topic must exist with exactly one partition")
            partition = TopicPartition(topic, 0)
            low, high = consumer.get_watermark_offsets(partition, timeout=15)
            if mirror.next_offset < low or mirror.next_offset > high:
                raise ProtocolError("CDC retention gap or topic reset; rebuild warehouse with a fresh snapshot")
            consumer.assign([TopicPartition(topic, 0, mirror.next_offset)])
            LOG.info("Resuming ordered CDC at offset %s", mirror.next_offset)
            last_status = 0
            while not stopping:
                message = consumer.poll(1.0)
                if message is not None:
                    if message.error():
                        raise KafkaException(message.error())
                    value = None if message.value() is None else json.loads(message.value())
                    durable = mirror.process(message.offset(), value)
                    if durable is not None:
                        # The warehouse is the checkpoint authority if this broker commit fails.
                        consumer.commit(offsets=[TopicPartition(topic, 0, durable)], asynchronous=False)
                if time.monotonic() - last_status >= 2:
                    _, high = consumer.get_watermark_offsets(partition, timeout=5)
                    mirror.polled(high)
                    last_status = time.monotonic()
        except BaseException as failure:
            mirror.abort()
            connection.execute("UPDATE warehouse.cdc_state SET caught_up = false, last_error = %s WHERE singleton",
                               (type(failure).__name__,))
            raise
        finally:
            mirror.abort()
            # A stopped consumer cannot advertise a current checkpoint indefinitely.
            connection.execute("UPDATE warehouse.cdc_state SET caught_up = false WHERE singleton")
            consumer.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if "--healthcheck" in sys.argv:
        try:
            sys.exit(healthcheck())
        except Exception:
            sys.exit(1)
    run()
