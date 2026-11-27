#!/bin/bash
set -euo pipefail
admin=/opt/kafka/bin
broker=kafka:9092

create_topic() {
  "$admin/kafka-topics.sh" --bootstrap-server "$broker" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1 \
    --config min.insync.replicas=1 --config cleanup.policy=delete \
    --config retention.ms=604800000 --config retention.bytes=268435456 \
    --config segment.bytes=16777216 --config segment.ms=3600000
}
create_topic sales.order-commands.v1 3
create_topic backoffice.write-commands.v1 3
create_topic backoffice.file-commands.v1 1
# Dữ liệu, BEGIN/END, heartbeat và thông báo snapshot dùng cùng partition.
create_topic banhang.reporting 1

for topic in banhang.connect.config banhang.connect.offsets banhang.connect.status; do
  "$admin/kafka-topics.sh" --bootstrap-server "$broker" --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 \
    --config min.insync.replicas=1 --config cleanup.policy=compact \
    --config segment.bytes=8388608 --config segment.ms=3600000 \
    --config max.compaction.lag.ms=3600000
done

# Không sửa topic có dữ liệu tự động; cấu hình cũ sai phải được xem xét cùng backlog.
for spec in 'sales.order-commands.v1 3 delete' 'backoffice.write-commands.v1 3 delete' \
  'backoffice.file-commands.v1 1 delete' 'banhang.reporting 1 delete' \
  'banhang.connect.config 1 compact' 'banhang.connect.offsets 1 compact' 'banhang.connect.status 1 compact'; do
  read -r topic expected policy <<< "$spec"
  description=$("$admin/kafka-topics.sh" --bootstrap-server "$broker" --describe --topic "$topic")
  if ! grep -Eq "PartitionCount:[[:space:]]+$expected([[:space:]]|$)" <<< "$description"; then
    echo "Sai số partition của $topic, yêu cầu $expected; không sửa topic có dữ liệu tự động." >&2
    exit 1
  fi
  for required in "ReplicationFactor:[[:space:]]+1([[:space:]]|$)" \
    "cleanup.policy=$policy([,[:space:]]|$)" "min.insync.replicas=1([,[:space:]]|$)"; do
    if ! grep -Eq "$required" <<< "$description"; then
      echo "Sai cấu hình topic $topic ($required); cần kiểm tra backlog trước khi đổi cấu hình." >&2
      exit 1
    fi
  done
  if [ "$policy" = delete ]; then
    for required in 'retention.ms=604800000([,[:space:]]|$)' 'retention.bytes=268435456([,[:space:]]|$)'; do
      if ! grep -Eq "$required" <<< "$description"; then
        echo "Retention của $topic khác giới hạn demo; cần kiểm tra trước khi tiếp tục." >&2
        exit 1
      fi
    done
  fi
done
