"""Đăng ký/update connector qua REST nội bộ; không in mật khẩu ra log."""
import json
import os
import time
import urllib.error
import urllib.request


def connector_config():
    return {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "tasks.max": "1",
        "database.hostname": "db",
        "database.port": "5432",
        "database.dbname": "banhang",
        "database.user": "cdc_reader",
        "database.password": os.environ["DB_CDC_PASSWORD"],
        "plugin.name": "pgoutput",
        "topic.prefix": "banhang",
        "slot.name": "pos_reporting",
        "slot.drop.on.stop": "false",
        "publication.name": "pos_reporting",
        "publication.autocreate.mode": "disabled",
        "schema.include.list": "app",
        "table.include.list": "app.sales_order,app.sales_order_line,app.sales_return,app.sales_return_line",
        "snapshot.mode": "initial",
        "snapshot.max.threads": "1",
        "provide.transaction.metadata": "true",
        "decimal.handling.mode": "string",
        "time.precision.mode": "connect",
        "tombstones.on.delete": "false",
        "heartbeat.interval.ms": "5000",
        "notification.enabled.channels": "sink",
        "notification.sink.topic.name": "banhang.notifications",
        "key.converter": "org.apache.kafka.connect.json.JsonConverter",
        "key.converter.schemas.enable": "false",
        "value.converter": "org.apache.kafka.connect.json.JsonConverter",
        "value.converter.schemas.enable": "false",
        "transforms": "ordered",
        "transforms.ordered.type": "org.apache.kafka.connect.transforms.RegexRouter",
        "transforms.ordered.regex": r"^(banhang\..*|__debezium-heartbeat\.banhang)$",
        "transforms.ordered.replacement": "banhang.reporting",
        "producer.override.acks": "all",
        "producer.override.enable.idempotence": "true",
        "producer.override.max.in.flight.requests.per.connection": "1",
    }


def request(base, path, data=None):
    payload = None if data is None else json.dumps(data).encode()
    call = urllib.request.Request(
        f"{base}{path}", data=payload, method="GET" if data is None else "PUT",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(call, timeout=15) as response:
        return json.load(response)


def main():
    base = os.environ.get("CONNECT_URL", "http://connect:8083").rstrip("/")
    config = connector_config()
    for attempt in range(30):
        try:
            try:
                current = request(base, "/connectors/pos-reporting/config")
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                current = {}
            if any(current.get(key) != value for key, value in config.items()):
                request(base, "/connectors/pos-reporting/config", config)
            break
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == 29:
                raise SystemExit(f"Không đăng ký được CDC ({type(error).__name__}); kiểm tra log Connect.")
            time.sleep(2)

    for _ in range(60):
        try:
            state = request(base, "/connectors/pos-reporting/status")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            # Sau PUT, Connect có thể chưa công bố trạng thái connector/task ngay.
            time.sleep(2)
            continue
        except (urllib.error.URLError, TimeoutError):
            time.sleep(2)
            continue
        tasks = state.get("tasks", [])
        if state.get("connector", {}).get("state") == "RUNNING" and tasks and all(
            task.get("state") == "RUNNING" for task in tasks
        ):
            print("CDC pos-reporting RUNNING; snapshot và apply theo dõi qua cdc-worker.")
            return
        if any(task.get("state") == "FAILED" for task in tasks):
            raise SystemExit("CDC task FAILED; kiểm tra log Connect, publication và replication slot.")
        time.sleep(2)
    raise SystemExit("CDC chưa RUNNING sau 120 giây.")


if __name__ == "__main__":
    main()
