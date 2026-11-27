"""Cảnh báo dung lượng Kafka; chạy theo lịch giám sát trên máy chủ triển khai."""
import argparse
import json
from pathlib import Path
import subprocess


TOPICS = ("sales.order-commands.v1", "banhang.reporting")
PARTITION_LIMIT = 256 * 1024 * 1024


def capacity_warnings(report, warn_percent):
    warnings = []
    seen = set()
    for broker in report.get("brokers", []):
        for log_dir in broker.get("logDirs", []):
            if log_dir.get("error") is not None:
                warnings.append(f"Kafka log directory error: {log_dir['error']}")
            for partition in log_dir.get("partitions", []):
                name = partition.get("partition", "")
                if not any(name.startswith(topic + "-") for topic in TOPICS):
                    continue
                seen.add(name)
                size = partition["size"]
                percent = size * 100 / PARTITION_LIMIT
                print(f"{name}: {size / 1024 / 1024:.1f} MiB / 256 MiB ({percent:.1f}%)")
                if percent >= warn_percent:
                    warnings.append(f"{name} đạt {percent:.1f}% giới hạn lưu; kiểm tra lag ngay.")
    expected = {f"sales.order-commands.v1-{i}" for i in range(3)} | {"banhang.reporting-0"}
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Project Compose sở hữu cụm cần kiểm tra")
    parser.add_argument("--env-file", action="append", required=True)
    parser.add_argument("--warn-percent", type=int, default=80)
    args = parser.parse_args()
    if not 1 <= args.warn_percent <= 100:
        parser.error("--warn-percent phải nằm trong 1..100")
    root = Path(__file__).resolve().parents[2]
    command = ["docker", "compose", "-p", args.project]
    for env_file in args.env_file:
        command += ["--env-file", env_file]
    command += ["-f", "deploy/docker-compose.yml", "-f", "deploy/compose.kafka.yml"]
    command += ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-log-dirs.sh",
                "--bootstrap-server", "kafka:9092", "--describe", "--broker-list", "1",
                "--topic-list", ",".join(TOPICS)]
    result = subprocess.run(command, cwd=root, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise SystemExit("Không đọc được Kafka log dirs; kiểm tra trạng thái đúng project Compose.")
    # Kafka CLI thêm dòng mô tả trước JSON.
    try:
        report = decode_report(result.stdout)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    warnings = capacity_warnings(report, args.warn_percent)
    if not report.get("brokers"):
        warnings.append("Không có broker trong báo cáo Kafka.")
    for warning in warnings:
        print(f"ALERT: {warning}")
    if warnings:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
