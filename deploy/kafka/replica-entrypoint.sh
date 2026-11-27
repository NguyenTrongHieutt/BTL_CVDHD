#!/bin/sh
set -eu

# Không tự xóa dữ liệu khi backup dang dở hoặc slot hết WAL. Người vận hành chọn lại volume.
: "${PGDATA:=/var/lib/postgresql/18/docker}"
: "${DB_REPLICATION_PASSWORD:?}"
: "${REPLICATION_SLOT:?}"
: "${REPLICA_NAME:?}"
if [ "$PGDATA" != /var/lib/postgresql/18/docker ]; then
  echo 'Replica chỉ cho phép PGDATA=/var/lib/postgresql/18/docker' >&2
  exit 1
fi
if [ "$(id -u)" = 0 ]; then
  mkdir -p "$PGDATA"
  chmod 700 "$PGDATA"
  chown postgres:postgres "$PGDATA" "$(dirname "$PGDATA")"
  exec gosu postgres /bin/sh "$0" "$@"
fi

umask 077
export PGPASSFILE=/var/lib/postgresql/18/.replica.pgpass
replica_pw=$(printf '%s' "$DB_REPLICATION_PASSWORD" | sed 's/\\/\\\\/g; s/:/\\:/g')
printf 'db:5432:*:replica_stream:%s\n' "$replica_pw" > "$PGPASSFILE"
unset replica_pw

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  if [ -n "$(ls -A "$PGDATA")" ]; then
    echo 'PGDATA chưa khởi tạo nhưng không trống; kiểm tra volume replica trước khi thử lại.' >&2
    exit 1
  fi
  pg_basebackup --dbname="host=db port=5432 user=replica_stream passfile=$PGPASSFILE application_name=$REPLICA_NAME" \
    --pgdata="$PGDATA" --write-recovery-conf --wal-method=stream \
    --slot="$REPLICATION_SLOT" --checkpoint=fast
fi
if [ ! -f "$PGDATA/standby.signal" ]; then
  echo 'Thiếu standby.signal; từ chối mở replica thành primary.' >&2
  exit 1
fi

exec docker-entrypoint.sh postgres \
  -c hba_file=/etc/postgresql/async-pg_hba.conf \
  -c "primary_conninfo=host=db port=5432 user=replica_stream dbname=banhang passfile=$PGPASSFILE application_name=$REPLICA_NAME" \
  -c "primary_slot_name=$REPLICATION_SLOT" \
  -c hot_standby=on -c shared_buffers=64MB -c max_connections=60 \
  -c temp_file_limit=128MB -c max_wal_size=256MB -c max_slot_wal_keep_size=512MB
