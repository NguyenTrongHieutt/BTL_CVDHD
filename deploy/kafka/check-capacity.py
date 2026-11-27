"""Cảnh báo dung lượng và lag Kafka trên máy chủ triển khai."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


TOPIC_PARTITIONS = {
    "sales.order-commands.v1": 3,
    "backoffice.write-commands.v1": 3,
    "backoffice.file-commands.v1": 1,
    "banhang.reporting": 1,
}
PARTITION_LIMIT = 256 * 1024 * 1024
COMMAND_GROUPS = {
    "sales-order-worker-v1": "sales.order-commands.v1",
    "backoffice-write-worker-v1": "backoffice.write-commands.v1",
    "backoffice-file-worker-v1": "backoffice.file-commands.v1",
}


def capacity_warnings(report, warn_percent):
    warnings = []
    seen = set()
    for broker in report.get("brokers", []):
        for log_dir in broker.get("logDirs", []):
            if log_dir.get("error") is not None:
                warnings.append(f"Kafka log directory error: {log_dir['error']}")
            for partition in log_dir.get("partitions", []):
                name = partition.get("partition", "")
                if not any(name.startswith(topic + "-") for topic in TOPIC_PARTITIONS):
                    continue
                seen.add(name)
                size = partition["size"]
                percent = size * 100 / PARTITION_LIMIT
                print(f"{name}: {size / 1024 / 1024:.1f} MiB / 256 MiB ({percent:.1f}%)")
                if percent >= warn_percent:
                    warnings.append(f"{name} đạt {percent:.1f}% giới hạn lưu; kiểm tra lag ngay.")
    expected = {f"{topic}-{i}" for topic, count in TOPIC_PARTITIONS.items() for i in range(count)}
    if seen != expected:
        warnings.append("Báo cáo thiếu hoặc sai partition của queue/reporting; kiểm tra topic.")
    return warnings


def decode_report(output):
    decoder = json.JSONDecoder()
    for position, character in enumerate(output):
        if character == "{":
            try:
                value, _ = decoder.raw_decode(output[position:])
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict) and "brokers" in value:
                return value
    raise ValueError("Kafka không trả báo cáo log dirs JSON.")


def lag_warnings(group_output, earliest_output, warn_lag):
    earliest = {}
    for line in earliest_output.splitlines():
        parts = line.strip().split(":")
        if len(parts) == 3 and parts[0] in COMMAND_GROUPS.values() and all(p.isdigit() for p in parts[1:]):
            earliest[parts[0], int(parts[1])] = int(parts[2])

    warnings = []
    seen = set()
    for line in group_output.splitlines():
        fields = line.split()
        if len(fields) < 7 or COMMAND_GROUPS.get(fields[0]) != fields[1] or not fields[2].isdigit():
            continue
        group, topic, partition, current, end, _, member = fields[:7]
        key = topic, int(partition)
        seen.add(key)
        if key not in earliest or not end.isdigit():
            warnings.append(f"Thiếu offset của {group}/{topic}-{partition}.")
            continue
        committed = int(current) if current.isdigit() else 0
        lag = int(end) - committed
        name = f"{group}/{topic}-{partition}"
        print(f"{name}: lag {lag}, offset {committed}, offset còn lưu sớm nhất {earliest[key]}")
        if committed < earliest[key]:
            warnings.append(f"{name} có offset chưa xác nhận nằm ngoài retention; đối chiếu lệnh với primary.")
        if lag > 0 and member == "-":
            warnings.append(f"{name} còn {lag} lệnh nhưng không có consumer.")
        elif lag >= warn_lag:
            warnings.append(f"{name} còn {lag} lệnh; kiểm tra worker.")
    expected = {(topic, partition) for topic in COMMAND_GROUPS.values()
                for partition in range(TOPIC_PARTITIONS[topic])}
    if seen != expected:
        warnings.append("Thiếu hoặc sai partition trong các consumer group; kiểm tra worker.")
    return warnings


def kafka_cli(command, tool, *args):
    try:
        result = subprocess.run([*command, f"/opt/kafka/bin/{tool}.sh", "--bootstrap-server", "kafka:9092", *args],
                                cwd=Path(__file__).resolve().parents[2], capture_output=True, encoding="utf-8",
                                errors="replace", timeout=60)
    except subprocess.TimeoutExpired as error:
        raise SystemExit(f"Hết thời gian chạy {tool}; kiểm tra Kafka và project Compose.") from error
    if result.returncode:
        raise SystemExit(f"Không chạy được {tool}; kiểm tra Kafka và project Compose.")
    return result.stdout


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Project Compose sở hữu cụm cần kiểm tra")
    parser.add_argument("--env-file", action="append", required=True)
    parser.add_argument("--warn-percent", type=int, default=80)
    parser.add_argument("--warn-lag", type=int, default=100, help="Số lệnh chờ trên mỗi partition")
    args = parser.parse_args()
    if not 1 <= args.warn_percent <= 100:
        parser.error("--warn-percent phải nằm trong 1..100")
    if args.warn_lag < 1:
        parser.error("--warn-lag phải lớn hơn 0")
    command = ["docker", "compose", "-p", args.project]
    for env_file in args.env_file:
        command += ["--env-file", env_file]
    command += ["-f", "deploy/docker-compose.yml", "-f", "deploy/compose.kafka.yml"]
    command += ["exec", "-T", "kafka"]
    output = kafka_cli(command, "kafka-log-dirs", "--describe", "--broker-list", "1", "--topic-list",
                       ",".join(TOPIC_PARTITIONS))
    # Kafka CLI thêm dòng mô tả trước JSON.
    try:
        report = decode_report(output)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    warnings = capacity_warnings(report, args.warn_percent)
    group_args = ["--describe"]
    for group in COMMAND_GROUPS:
        group_args.extend(["--group", group])
    groups = kafka_cli(command, "kafka-consumer-groups", *group_args)
    topic_pattern = "|".join(topic.replace(".", r"\.") for topic in COMMAND_GROUPS.values())
    earliest = kafka_cli(command, "kafka-get-offsets", "--topic", topic_pattern, "--time", "earliest")
    warnings.extend(lag_warnings(groups, earliest, args.warn_lag))
    if not report.get("brokers"):
        warnings.append("Không có broker trong báo cáo Kafka.")
    for warning in warnings:
        print(f"ALERT: {warning}")
    if warnings:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
